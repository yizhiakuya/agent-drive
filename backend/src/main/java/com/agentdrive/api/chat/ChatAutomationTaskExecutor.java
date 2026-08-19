package com.agentdrive.api.chat;

import com.agentdrive.files.FileStorageService;
import com.agentdrive.tasks.AutomationTaskExecutor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 执行自动化任务中的受限聊天，并把结果写成 owner-scoped Markdown 报告。
 *
 * <p>任务 prompt 只允许查看、移动、重命名、复制、建目录和写文本，明确禁止删除；
 * runtime 最长阻塞十分钟。没有规则且没有自定义 prompt 时直接返回 skipped，避免
 * 无意义地调用模型；成功结果写入 {@code Agent/notes/自动化报告-日期.md}。
 */
@Component
@Profile("java-chat")
public final class ChatAutomationTaskExecutor implements AutomationTaskExecutor {
    private final ChatRuntime runtime;
    private final FileStorageService files;

    /**
     * 创建自动化任务执行器。
     *
     * @param runtime 执行受限自动化聊天的 runtime。
     * @param files 将执行报告以 UTF-8 文本写入用户文件区的存储服务。
     */
    public ChatAutomationTaskExecutor(ChatRuntime runtime, FileStorageService files) {
        this.runtime = runtime;
        this.files = files;
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
}
