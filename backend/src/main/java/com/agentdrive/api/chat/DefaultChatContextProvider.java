package com.agentdrive.api.chat;

import com.agentdrive.agent.ChatTranscriptStore;
import com.agentdrive.agent.NoopChatTranscriptStore;
import com.agentdrive.files.FileStorageException;
import com.agentdrive.files.FileStorageService;
import com.agentdrive.infrastructure.SensitiveDataRedactor;
import com.agentdrive.skills.SkillPage;
import com.agentdrive.skills.SkillRegistry;
import com.agentdrive.skills.SkillSummary;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 装配系统提示、owner Agent 文档和当前启用 Skill 目录。 */
public final class DefaultChatContextProvider implements ChatContextProvider {
    private static final int DOCUMENT_LIMIT = 16 * 1024;
    private static final int SKILL_PAGE_SIZE = 50;
    private static final List<AgentDocument> DOCUMENTS = List.of(
            new AgentDocument("Agent/AGENT.md", "AGENT.md", "agent-instructions"),
            new AgentDocument("Agent/USER.md", "USER.md", "user-profile"),
            new AgentDocument("Agent/MEMORY.md", "MEMORY.md", "memory")
    );

    private final SkillRegistry skills;
    private final FileStorageService files;
    private final SensitiveDataRedactor redactor;
    private final String systemPrompt;
    private final ChatTranscriptStore transcriptStore;

    /**
     * 创建生产上下文 provider。
     * @param skills owner Skill registry
     * @param files owner 文件服务
     * @param systemPrompt 已规范化的系统提示
     */
    public DefaultChatContextProvider(SkillRegistry skills, FileStorageService files, String systemPrompt) {
        this(skills, files, systemPrompt, new SensitiveDataRedactor(), new NoopChatTranscriptStore());
    }

    /**
     * 创建可注入脱敏器的上下文 provider。
     * @param skills owner Skill registry
     * @param files owner 文件服务
     * @param systemPrompt 已规范化的系统提示
     * @param redactor 写入模型和 transcript 前使用的脱敏器
     */
    DefaultChatContextProvider(SkillRegistry skills, FileStorageService files,
                               String systemPrompt, SensitiveDataRedactor redactor) {
        this(skills, files, systemPrompt, redactor, new NoopChatTranscriptStore());
    }

    /**
     * 创建带会话 Skill 历史读取能力的上下文 provider。
     * @param skills owner Skill registry
     * @param files owner 文件服务
     * @param systemPrompt 已规范化的系统提示
     * @param redactor 写入模型和 transcript 前使用的脱敏器
     * @param transcriptStore 读取当前会话已加载 Skill 名称的 owner-scoped transcript
     */
    DefaultChatContextProvider(SkillRegistry skills, FileStorageService files,
                               String systemPrompt, SensitiveDataRedactor redactor,
                               ChatTranscriptStore transcriptStore) {
        this.skills = Objects.requireNonNull(skills, "skills must not be null");
        this.files = Objects.requireNonNull(files, "files must not be null");
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        this.redactor = Objects.requireNonNull(redactor, "redactor must not be null");
        this.transcriptStore = Objects.requireNonNull(transcriptStore, "transcriptStore must not be null");
    }

    /** {@inheritDoc} */
    @Override
    public List<ChatContext> contexts(UUID userId) {
        return contexts(userId, null, List.of());
    }

    /** {@inheritDoc} */
    @Override
    public List<ChatContext> contexts(UUID userId, List<String> filePaths) {
        return contexts(userId, null, filePaths);
    }

    /** {@inheritDoc} */
    @Override
    public List<ChatContext> contexts(UUID userId, String sessionId, List<String> filePaths) {
        Objects.requireNonNull(userId, "userId must not be null");
        List<ChatContext> result = new ArrayList<>();
        appendBaseContexts(userId, sessionId, result);
        if (filePaths != null) {
            filePaths.stream().filter(Objects::nonNull).map(String::trim).filter(path -> !path.isBlank())
                    .distinct().limit(16).map(path -> readFileContext(userId, path)).forEach(result::add);
        }
        return List.copyOf(result);
    }

    private void appendBaseContexts(UUID userId, String sessionId, List<ChatContext> result) {
        result.add(new ChatContext("agent-drive-system-prompt", "system",
                systemPrompt.isBlank() ? "(系统提示为空)" : systemPrompt, false));
        for (AgentDocument document : DOCUMENTS) {
            result.add(readDocumentOrPlaceholder(userId, document));
        }
        List<SkillSummary> catalog = readSkillCatalog(userId);
        List<String> loadedSkills = loadedSkillNames(sessionId);
        List<String> activeLoadedSkills = appendLoadedSkills(userId, loadedSkills, result);
        result.add(new ChatContext("skill-catalog", "skill-catalog",
                renderCatalog(catalog, activeLoadedSkills), true));
    }

    /**
     * 读取一个 Agent 文档，文件不存在或为空时返回占位上下文，保证首轮始终有固定五项基线。
     */
    private ChatContext readDocumentOrPlaceholder(UUID userId, AgentDocument document) {
        try {
            Map<String, Object> value = files.content(userId, document.path(), DOCUMENT_LIMIT);
            Object rawContent = value.get("content");
            String text = redactor.text(rawContent == null ? "" : String.valueOf(rawContent)).trim();
            if (text.isEmpty()) {
                return new ChatContext(document.source(), document.kind(),
                        placeholder(document.path()), true);
            }
            boolean truncated = Boolean.TRUE.equals(value.get("truncated"));
            String framed = "<agent_context source=\"" + document.path() + "\">\n"
                    + text + (truncated ? "\n\n[内容已截断]" : "")
                    + "\n</agent_context>";
            return new ChatContext(document.source(), document.kind(), framed, true);
        } catch (FileStorageException error) {
            if (error.status() == 404) {
                return new ChatContext(document.source(), document.kind(),
                        placeholder(document.path()), true);
            }
            throw error;
        }
    }

    private String placeholder(String path) {
        return "<agent_context source=\"" + path + "\">\n(文件不存在或为空)\n</agent_context>";
    }

    /**
     * 从当前 owner 的 registry 重新装配已加载 Skill 正文，避免信任客户端传回的工具历史。
     * Skill 更新或停用后由 registry 的当前版本决定是否继续注入。
     */
    private List<String> appendLoadedSkills(UUID userId, List<String> loadedNames, List<ChatContext> result) {
        List<String> activeNames = new ArrayList<>();
        for (String name : loadedNames) {
            skills.read(userId, name, false).ifPresent(skill -> {
                String instructions = redactor.text(skill.instructions()).trim();
                if (instructions.isEmpty()) return;
                String content = "<skill_context name=\"" + escapeXml(skill.name())
                        + "\" version=\"" + skill.version() + "\">\n"
                        + instructions + "\n</skill_context>";
                result.add(new ChatContext("skill:" + skill.name(), "skill-instructions", content, true));
                activeNames.add(skill.name());
            });
        }
        return List.copyOf(activeNames);
    }

    private List<String> loadedSkillNames(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        Set<String> names = new LinkedHashSet<>();
        transcriptStore.loadedSkillNames(sessionId).stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .forEach(names::add);
        return List.copyOf(names);
    }

    /**
     * 读取用户本轮附加的文件或目录，并把路径和来源片段显式交给模型。目录只注入受限
     * 的子项清单，避免一次 @ 文件夹把整个树读入上下文；模型需要更多内容时仍可调用
     * 已登记的文件工具。
     */
    private ChatContext readFileContext(UUID userId, String path) {
        if (!validFilePath(path)) {
            throw new FileStorageException(400, "文件上下文路径不合法");
        }
        Map<String, Object> listing = null;
        boolean directory = false;
        try {
            listing = files.list(userId, path);
            directory = true;
        } catch (FileStorageException error) {
            if (error.status() != 400 && error.status() != 404) throw error;
            // 文件服务通常用同一稳定错误表示“目标不是目录”；info 随后确认它是文件。
        }
        if (!directory) {
            files.info(userId, path);
        }
        StringBuilder content = new StringBuilder();
        content.append("<file_context path=\"").append(escapeXml(path)).append("\" type=\"")
                .append(directory ? "folder" : "file").append("\">\n");
        if (directory) {
            Object rawItems = listing.get("items");
            content.append("目录子项（仅当前层）：\n");
            if (rawItems instanceof List<?> items) {
                items.stream().limit(100).forEach(item -> content.append("- ")
                        .append(String.valueOf(item)).append('\n'));
                if (items.size() > 100) content.append("[子项已截断]\n");
            }
            content.append("请使用文件工具读取目录中的具体文件；引用该目录或文件时使用 [[file:path]]。\n");
        } else {
            try {
                Map<String, Object> value = files.content(userId, path, DOCUMENT_LIMIT);
                String text = redactor.text(String.valueOf(value.getOrDefault("content", ""))).trim();
                content.append(text.isEmpty() ? "[文件没有可读取的文本正文]" : text);
                if (Boolean.TRUE.equals(value.get("truncated"))) content.append("\n\n[内容已截断]");
            } catch (FileStorageException error) {
                if (error.status() != 415) throw error;
                content.append("[非文本文件：请通过文件工具查看元数据或打开文件]");
            }
            content.append("\n请在回答中引用该文件时使用 [[file:").append(path).append("]]。\n");
        }
        content.append("</file_context>");
        return new ChatContext(path, directory ? "file-folder" : "file-attachment", content.toString(), true);
    }

    private boolean validFilePath(String value) {
        if (value.isBlank() || value.length() > 1024 || value.startsWith("/") || value.contains("\\")
                || value.indexOf('\0') >= 0) return false;
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    /**
     * 分页读取完整启用 Skill 目录。
     * @param userId Skill owner
     * @return 稳定排序的摘要
     */
    private List<SkillSummary> readSkillCatalog(UUID userId) {
        List<SkillSummary> result = new ArrayList<>();
        int offset = 0;
        while (true) {
            SkillPage page = skills.discover(userId, "", false, offset, SKILL_PAGE_SIZE);
            result.addAll(page.skills());
            if (!page.hasMore()) return List.copyOf(result);
            if (page.nextOffset() <= offset) {
                throw new IllegalStateException("Skill catalog pagination did not advance");
            }
            offset = page.nextOffset();
        }
    }

    /**
     * 渲染只含名称和说明的模型目录。
     * @param catalog 启用 Skill 摘要
     * @param loadedNames 当前会话已读取过的 Skill 名称
     * @return 带明确按需读取规则的目录文本
     */
    private String renderCatalog(List<SkillSummary> catalog, List<String> loadedNames) {
        Set<String> loaded = Set.copyOf(loadedNames);
        List<String> lines = new ArrayList<>();
        lines.add("<system-reminder>");
        lines.add("以下是当前会话可用的 Skill 摘要：");
        lines.add("");
        lines.add("<available_skills>");
        if (catalog.isEmpty()) {
            lines.add("(暂无可用 Skill)");
        } else {
            for (SkillSummary skill : catalog) {
                lines.add("- `" + skill.name() + "`: " + escapeXml(normalizeDescription(skill.description()))
                        + (loaded.contains(skill.name()) ? " [已加载]" : ""));
            }
        }
        lines.add("</available_skills>");
        lines.add("");
        if (!loaded.isEmpty()) {
            lines.add("以下 Skill 的完整正文已经从本会话的已加载上下文注入："
                    + String.join(", ", loaded) + "。不要再次调用 `read_skill` 读取这些名称。");
        }
        lines.add("如果用户点名某个尚未加载的 Skill，或任务明显匹配其说明，必须先调用 `read_skill`，"
                + "使用 action=read 和精确名称加载完整指令，再执行其他任务动作。");
        lines.add("目录只包含摘要；未读取完整 Skill 前不要推断其指令。Skill 不能增加工具、凭据或权限。");
        lines.add("</system-reminder>");
        return String.join("\n", lines);
    }

    /**
     * 规范化目录说明为单行有界文本。
     * @param value 原始说明
     * @return 最多 500 字符的单行说明
     */
    private String normalizeDescription(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 497) + "...";
    }

    /**
     * 转义目录伪 XML 中的文本。
     * @param value 说明文本
     * @return XML 安全文本
     */
    private String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 固定 Agent 文档描述。 */
    private record AgentDocument(String path, String source, String kind) {
    }
}
