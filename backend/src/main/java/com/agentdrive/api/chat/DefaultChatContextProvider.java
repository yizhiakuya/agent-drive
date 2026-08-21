package com.agentdrive.api.chat;

import com.agentdrive.files.FileStorageException;
import com.agentdrive.files.FileStorageService;
import com.agentdrive.infrastructure.SensitiveDataRedactor;
import com.agentdrive.skills.SkillPage;
import com.agentdrive.skills.SkillRegistry;
import com.agentdrive.skills.SkillSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /**
     * 创建生产上下文 provider。
     * @param skills owner Skill registry
     * @param files owner 文件服务
     * @param systemPrompt 已规范化的系统提示
     */
    public DefaultChatContextProvider(SkillRegistry skills, FileStorageService files, String systemPrompt) {
        this(skills, files, systemPrompt, new SensitiveDataRedactor());
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
        this.skills = Objects.requireNonNull(skills, "skills must not be null");
        this.files = Objects.requireNonNull(files, "files must not be null");
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        this.redactor = Objects.requireNonNull(redactor, "redactor must not be null");
    }

    /** {@inheritDoc} */
    @Override
    public List<ChatContext> contexts(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        List<ChatContext> result = new ArrayList<>();
        if (!systemPrompt.isBlank()) {
            result.add(new ChatContext("agent-drive-system-prompt", "system", systemPrompt, false));
        }
        for (AgentDocument document : DOCUMENTS) {
            result.add(readDocumentOrPlaceholder(userId, document));
        }
        result.add(new ChatContext("skill-catalog", "skill-catalog", renderCatalog(readSkillCatalog(userId)), true));
        return List.copyOf(result);
    }

    /**
     * 读取一个可选 Agent 文档，文件不存在或为空时返回占位上下文，保证前端始终能看到 5 项基线。
     * @param userId 文档 owner
     * @param document 固定文档描述
     * @return 始终有内容的上下文
     */
    private ChatContext readDocumentOrPlaceholder(UUID userId, AgentDocument document) {
        try {
            Map<String, Object> value = files.content(userId, document.path(), DOCUMENT_LIMIT);
            Object rawContent = value.get("content");
            String text = redactor.text(rawContent == null ? "" : String.valueOf(rawContent)).trim();
            if (text.isEmpty()) {
                String placeholder = "<agent_context source=\"" + document.path() + "\">\n(文件不存在或为空)\n</agent_context>";
                return new ChatContext(document.source(), document.kind(), placeholder, true);
            }
            boolean truncated = Boolean.TRUE.equals(value.get("truncated"));
            String framed = "<agent_context source=\"" + document.path() + "\">\n"
                    + text + (truncated ? "\n\n[内容已截断]" : "")
                    + "\n</agent_context>";
            return new ChatContext(document.source(), document.kind(), framed, true);
        } catch (FileStorageException error) {
            if (error.status() == 404) {
                String placeholder = "<agent_context source=\"" + document.path() + "\">\n(文件不存在或为空)\n</agent_context>";
                return new ChatContext(document.source(), document.kind(), placeholder, true);
            }
            throw error;
        }
    }

    private java.util.Optional<ChatContext> readDocument(UUID userId, AgentDocument document) {
        try {
            Map<String, Object> value = files.content(userId, document.path(), DOCUMENT_LIMIT);
            Object rawContent = value.get("content");
            String text = redactor.text(rawContent == null ? "" : String.valueOf(rawContent)).trim();
            if (text.isEmpty()) return java.util.Optional.empty();
            boolean truncated = Boolean.TRUE.equals(value.get("truncated"));
            String framed = "<agent_context source=\"" + document.path() + "\">\n"
                    + text + (truncated ? "\n\n[内容已截断]" : "")
                    + "\n</agent_context>";
            return java.util.Optional.of(new ChatContext(
                    document.source(), document.kind(), framed, true));
        } catch (FileStorageException error) {
            if (error.status() == 404) return java.util.Optional.empty();
            throw error;
        }
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
     * @return 带明确按需读取规则的目录文本
     */
    private String renderCatalog(List<SkillSummary> catalog) {
        List<String> lines = new ArrayList<>();
        lines.add("<system-reminder>");
        lines.add("以下是当前会话可用的 Skill 摘要：");
        lines.add("");
        lines.add("<available_skills>");
        if (catalog.isEmpty()) {
            lines.add("(暂无可用 Skill)");
        } else {
            for (SkillSummary skill : catalog) {
                lines.add("- `" + skill.name() + "`: " + escapeXml(normalizeDescription(skill.description())));
            }
        }
        lines.add("</available_skills>");
        lines.add("");
        lines.add("如果用户点名某个 Skill，或任务明显匹配其说明，必须先调用 `read_skill`，"
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
