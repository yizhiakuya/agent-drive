package com.agentdrive.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 记录当前 Agent 会话的可视化执行计划。
 *
 * <p>计划只作为本轮对话的 UI 状态和模型上下文结果，不创建后台任务、队列或独立
 * Worker。每次调用都返回完整规范化计划，前端可以在流式 trace 和 done 事件中刷新
 * 当前计划。</p>
 */
public final class PlanTool implements AgentTool {
    private static final int MAX_STEPS = 32;
    private static final int MAX_TEXT_LENGTH = 500;
    private static final List<String> STATUSES = List.of("pending", "in_progress", "done", "skipped", "failed");

    private final ObjectMapper objectMapper;

    /**
     * 创建计划工具。
     * @param objectMapper 计划请求和结果 JSON 映射器
     */
    public PlanTool(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /** {@inheritDoc} */
    @Override
    public String toolName() {
        return "plan";
    }

    /** {@inheritDoc} */
    @Override
    public String executeRaw(String rawArguments, AgentToolContext context) throws JsonProcessingException {
        PlanRequest request = objectMapper.readValue(rawArguments, PlanRequest.class);
        return execute(request.action(), request.plan());
    }

    /**
     * 设置或更新当前会话的完整执行计划。
     *
     * @param action {@code set} 创建计划，{@code update} 更新计划
     * @param plan 完整计划步骤，每项包含 {@code text} 和可选 {@code status}
     * @return 规范化的完整计划
     */
    @Tool(name = "plan", value = {
            "Create or update the current conversation execution plan. This is visual session state only; it never creates a background task or queue.",
            "Use action=set for a new multi-step plan and action=update after progress changes. Provide the complete plan array each time.",
            "Each plan item must be an object with text and optional status: pending, in_progress, done, skipped, or failed."
    })
    public String execute(
            @P(name = "action", value = "set or update", required = false) String action,
            @P(name = "plan", value = "Complete plan step array with text and status", required = false)
            List<Map<String, Object>> plan
    ) {
        String normalizedAction = action == null || action.isBlank()
                ? "set" : action.trim().toLowerCase(Locale.ROOT);
        if (!"set".equals(normalizedAction) && !"update".equals(normalizedAction)) {
            return error("invalid_action", "action must be set or update");
        }
        if (plan == null || plan.isEmpty()) {
            return error("invalid_plan", "plan must contain at least one step");
        }
        if (plan.size() > MAX_STEPS) {
            return error("invalid_plan", "plan contains too many steps");
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> raw : plan) {
            if (raw == null) return error("invalid_plan", "plan steps must be objects");
            Object rawText = raw.get("text");
            String text = rawText == null ? "" : String.valueOf(rawText).trim();
            if (text.isBlank() || text.length() > MAX_TEXT_LENGTH) {
                return error("invalid_plan", "each plan step text must contain 1 to 500 characters");
            }
            String status = raw.get("status") == null ? "pending" : String.valueOf(raw.get("status")).trim();
            if (!STATUSES.contains(status)) {
                return error("invalid_plan", "plan step status must be pending, in_progress, done, skipped, or failed");
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("text", text);
            step.put("status", status);
            normalized.add(step);
        }
        return json(Map.of("ok", true, "action", normalizedAction, "plan", normalized));
    }

    /** {@inheritDoc} */
    @Override
    public OperationDefinition definitionFor(Map<String, Object> arguments, AgentToolContext context) {
        return OperationDefinition.internal("plan", "记录当前会话的可视化执行计划", "green");
    }

    private String error(String code, String detail) {
        return json(Map.of("ok", false, "error", code, "detail", detail));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to encode plan result", error);
        }
    }

    private record PlanRequest(String action, List<Map<String, Object>> plan) {
    }
}
