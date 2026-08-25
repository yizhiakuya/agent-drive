package com.agentdrive.api.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 非流式聊天完成结果。
 *
 * <p>除最终回复外，还携带工具步骤、计划、模型用量、上下文用量、确认状态和路由信息。
 * 构造器将集合字段规范化为不可变非空值；{@link #doneData()} 只组装 SSE done 事件
 * 需要的元数据，不重复放入最终回复正文。
 */
public record ChatResponse(
        String reply,
        @JsonProperty("tool_trace") List<Map<String, Object>> toolTrace,
        int steps,
        @JsonProperty("latency_ms") long latencyMs,
        @JsonProperty("pending_confirmation") Map<String, Object> pendingConfirmation,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("needs_summary") boolean needsSummary,
        String routed,
        List<Map<String, Object>> plan,
        Map<String, Object> usage,
        @JsonProperty("context_usage") Map<String, Object> contextUsage,
        boolean truncated
) {
    /**
     * 规范化聊天完成结果中的集合字段。
     *
     * @param reply 最终助手文本，空值转为空字符串。
     * @param toolTrace 工具执行展示记录。
     * @param steps Agent 执行步数。
     * @param latencyMs 从请求开始到完成的耗时毫秒数。
     * @param pendingConfirmation 待用户确认的 red 操作参数，可为空。
     * @param sessionId 关联会话 ID，可为空。
     * @param needsSummary 是否需要异步生成会话摘要。
     * @param routed 实际采用的 chat/task 路由，可为空。
     * @param plan Agent 计划步骤。
     * @param usage Provider 返回的 token 用量。
     * @param contextUsage 上下文窗口使用情况。
     * @param truncated 是否因输出或步数限制截断。
     */
    public ChatResponse {
        reply = reply == null ? "" : reply;
        toolTrace = toolTrace == null ? List.of() : List.copyOf(toolTrace);
        plan = plan == null ? List.of() : List.copyOf(plan);
        usage = usage == null ? Map.of() : Map.copyOf(usage);
        contextUsage = contextUsage == null ? Map.of() : Map.copyOf(contextUsage);
    }

    /**
     * 构造流式 {@code done} 事件的数据部分。
     *
     * @return 包含执行统计、工具轨迹、计划、用量和可选会话/确认字段的可序列化映射。
     */
    public Map<String, Object> doneData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("steps", steps);
        data.put("latency_ms", latencyMs);
        data.put("total_elapsed_ms", latencyMs);
        data.put("tool_trace", toolTrace);
        data.put("plan", plan);
        data.put("usage", usage);
        data.put("context_usage", contextUsage);
        data.put("needs_summary", needsSummary);
        data.put("truncated", truncated);
        if (sessionId != null) {
            data.put("session_id", sessionId);
        }
        if (pendingConfirmation != null) {
            data.put("pending_confirmation", pendingConfirmation);
        }
        if (routed != null) {
            data.put("routed", routed);
        }
        return data;
    }
}
