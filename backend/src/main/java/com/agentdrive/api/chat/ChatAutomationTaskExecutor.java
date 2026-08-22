package com.agentdrive.api.chat;

import com.agentdrive.files.FileStorageService;
import com.agentdrive.auth.ConversationSessionService;
import com.agentdrive.progress.TaskProgressReporter;
import com.agentdrive.tasks.AutomationTaskExecutor;
import com.agentdrive.tasks.ChatTaskExecutor;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 执行后台聊天任务和受限自动化任务；聊天结果写入既有会话 transcript。 */
@Component
@Profile("java-chat")
public final class ChatAutomationTaskExecutor implements AutomationTaskExecutor, ChatTaskExecutor {
    private final ChatRuntime runtime;
    private final FileStorageService files;
    private final ConversationSessionService sessions;
    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "agent-drive-chat-task-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 创建自动化任务执行器。
     *
     * @param runtime 执行受限自动化聊天的 runtime。
     * @param files 将执行报告以 UTF-8 文本写入用户文件区的存储服务。
     */
    @Autowired
    public ChatAutomationTaskExecutor(ChatRuntime runtime, FileStorageService files,
                                      ConversationSessionService sessions) {
        this.runtime = runtime;
        this.files = files;
        this.sessions = sessions;
    }

    /** 兼容旧测试和非后台自动化调用。 */
    public ChatAutomationTaskExecutor(ChatRuntime runtime, FileStorageService files) {
        this(runtime, files, null);
    }

    /** 应用关闭时释放后台租约线程。 */
    @PreDestroy
    void shutdown() {
        heartbeatExecutor.shutdownNow();
    }

    /**
     * 执行 owner-scoped chat.run。后台任务不携带浏览器能力或确认参数，只允许运行绿色工具。
     * @param userId 任务 owner
     * @param payload 包含 session_id、message、thinking_level、model 和可选 file_context 的受控 payload
     * @param progress 任务进度/租约回调
     * @return 任务结果摘要
     */
    @Override
    public Map<String, Object> execute(UUID userId, Map<String, Object> payload, TaskProgressReporter progress) {
        if (sessions == null) throw new IllegalStateException("chat session service unavailable");
        Map<String, Object> safePayload = payload == null ? Map.of() : payload;
        String sessionId = text(safePayload.get("session_id"));
        if (sessionId.isBlank()) throw new IllegalArgumentException("session_id is required");
        ConversationSessionService.SessionDetails details = sessions.getOwned(userId, sessionId);
        String message = text(safePayload.get("message"));
        if (message.isBlank()) throw new IllegalArgumentException("message is required");
        List<Map<String, Object>> history = history(details.messages());
        progress.reportNow(0, 0, "聊天任务：开始执行");
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleWithFixedDelay(
                progress::heartbeat, 30, 30, TimeUnit.SECONDS);
        try {
            ChatRequest request = new ChatRequest(
                    message, history, List.of(), sessionId,
                    text(safePayload.get("thinking_level")).isBlank() ? "auto" : text(safePayload.get("thinking_level")),
                    null, "task-" + sessionId, List.of(), text(safePayload.get("model")),
                    paths(safePayload.get("file_context")), "auto", List.of())
                    .withAuthenticatedUserId(userId);
            ChatResponse response = runtime.complete(request)
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(Duration.ofMinutes(30));
            if (response == null) throw new IllegalStateException("chat runtime returned no response");
            if (response.pendingConfirmation() != null) {
                throw new IllegalStateException("background chat cannot execute confirmation-required operations");
            }
            progress.reportNow(1, 1, "聊天任务：执行完成");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("session_id", sessionId);
            result.put("reply", response.reply());
            result.put("steps", response.steps());
            result.put("latency_ms", response.latencyMs());
            result.put("routed", response.routed());
            result.put("truncated", response.truncated());
            return result;
        } finally {
            heartbeat.cancel(true);
        }
    }

    /**
     * 执行一项自动化聊天任务并写入报告。
     *
     * @param userId 自动化任务所属用户 UUID，决定工具和报告的 owner 范围。
     * @param payload 任务参数；支持 rules 集合或 prompt 文本。
     * @return skipped、执行步数、耗时和报告路径等任务结果。
     * @throws IllegalStateException runtime 在十分钟内没有返回结果时抛出。
     */
    @Override
    public Map<String, Object> execute(UUID userId, Map<String, Object> payload) {
        String rules = rulesText(payload == null ? Map.of() : payload);
        String configuredPrompt = payload == null ? "" : text(payload.get("prompt"));
        if (rules.isBlank() && configuredPrompt.isBlank()) {
            return Map.of("ok", true, "skipped", "no automation rules", "rules", 0);
        }
        String prompt = configuredPrompt.isBlank()
                ? "你是网盘的自动化执行器。\n规则清单：\n" + rules
                : configuredPrompt;
        prompt += "\n\n只执行整理类动作：查看、移动、重命名、复制、创建目录或写文本；严禁删除文件。"
                + "先 discover 再调用 operation。无法完成的部分说明原因。执行结果将由 worker 写入自动化报告。";
        ChatRequest request = new ChatRequest(prompt, List.of(), List.of(), null, "auto")
                .withAuthenticatedUserId(userId);
        ChatResponse response = runtime.complete(request).block(Duration.ofMinutes(10));
        if (response == null) throw new IllegalStateException("automation chat runtime returned no response");

        String reportPath = "Agent/notes/自动化报告-" + LocalDate.now() + ".md";
        String report = "# 自动化执行报告\n\n"
                + "## 规则\n" + (rules.isBlank() ? "（由任务 prompt 指定）" : rules) + "\n\n"
                + "## 执行结果\n" + response.reply() + "\n\n"
                + "- steps: " + response.steps() + "\n"
                + "- latency_ms: " + response.latencyMs() + "\n";
        files.writeText(userId, reportPath, report, true);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("rules", rules.isBlank() ? 0 : rules.split("\n").length);
        result.put("steps", response.steps());
        result.put("latency_ms", response.latencyMs());
        result.put("report", reportPath);
        return result;
    }

    /**
     * 将 rules 参数转换成编号无关的 Markdown 规则清单。
     *
     * @param payload 自动化任务 payload。
     * @return 集合元素按换行拼接并带 {@code - } 前缀的规则文本；非集合值按单个文本处理。
     */
    private String rulesText(Map<String, Object> payload) {
        Object value = payload.get("rules");
        if (value instanceof Collection<?> values) {
            return values.stream().map(this::text).filter(item -> !item.isBlank()).reduce((left, right) -> left + "\n- " + right)
                    .map(valueText -> valueText.startsWith("- ") ? valueText : "- " + valueText).orElse("");
        }
        return text(value);
    }

    /**
     * 将自动化 payload 值转换为去除首尾空白的文本。
     *
     * @param value 待转换的规则或 prompt 值。
     * @return null 转为空字符串，否则返回裁剪后的字符串。
     */
    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private List<Map<String, Object>> history(List<Map<String, Object>> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            if (!"user".equals(message.get("role")) && !"assistant".equals(message.get("role"))) continue;
            String content = text(message.get("content"));
            if (content.isBlank()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", String.valueOf(message.get("role")));
            item.put("content", content);
            result.add(item);
        }
        return List.copyOf(result);
    }

    private List<String> paths(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            String path = text(item);
            if (!path.isBlank()) result.add(path);
        }
        return List.copyOf(result);
    }
}
