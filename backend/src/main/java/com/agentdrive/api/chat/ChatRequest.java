package com.agentdrive.api.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 聊天完成和流式聊天的请求模型。
 *
 * <p>客户端可提交当前消息、历史消息、确认参数、会话 ID 和思考等级；构造器会把
 * 可空列表规范化为不可变空列表，并把缺省思考等级设为 {@code auto}。认证用户 ID
 * 使用 {@code JsonIgnore}，只由服务端在请求准备阶段注入，模型不能从请求体提供；
 * {@code frontend_capabilities} 只是当前浏览器的 UI 动作清单，不授予后端 API 权限。
 */
public record ChatRequest(
        @NotBlank String message,
        List<Map<String, Object>> history,
        List<Map<String, Object>> confirmations,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("thinking_level")
        @Pattern(regexp = "auto|low|medium|high") String thinkingLevel,
        @com.fasterxml.jackson.annotation.JsonIgnore UUID authenticatedUserId,
        @com.fasterxml.jackson.annotation.JsonIgnore String requestId,
        @JsonProperty("frontend_capabilities") List<Map<String, Object>> frontendCapabilities,
        @JsonProperty("model") @Size(max = 256) String model
) {
    /**
     * 创建未注入认证 owner 的客户端请求。
     *
     * @param message 本轮用户消息，必须非空。
     * @param history 客户端历史消息；为空时转为空不可变列表。
     * @param confirmations 本轮确认/重放参数；为空时转为空不可变列表。
     * @param sessionId 可选会话 ID。
     * @param thinkingLevel 思考等级，支持 auto、low、medium、high，空值归一化为 auto。
     */
    public ChatRequest(String message,
                       List<Map<String, Object>> history,
                       List<Map<String, Object>> confirmations,
                       String sessionId,
                       String thinkingLevel) {
        this(message, history, confirmations, sessionId, thinkingLevel, null, null, null, null);
    }

    /**
     * 创建带服务端 owner 和请求 ID 的兼容请求副本。
     *
     * @param message 本轮消息
     * @param history 客户端历史
     * @param confirmations 本轮确认参数
     * @param sessionId 会话 ID
     * @param thinkingLevel 思考等级
     * @param authenticatedUserId 服务端认证 owner
     * @param requestId 聊天流关联 ID
     */
    public ChatRequest(String message,
                       List<Map<String, Object>> history,
                       List<Map<String, Object>> confirmations,
                       String sessionId,
                       String thinkingLevel,
                       UUID authenticatedUserId,
                       String requestId) {
        this(message, history, confirmations, sessionId, thinkingLevel,
                authenticatedUserId, requestId, null, null);
    }

    /**
     * 保留旧的八参数内部构造器，并使用默认模型。
     *
     * @param message 本轮消息
     * @param history 客户端历史
     * @param confirmations 本轮确认参数
     * @param sessionId 会话 ID
     * @param thinkingLevel 思考等级
     * @param authenticatedUserId 服务端认证 owner
     * @param requestId 服务端请求关联 ID
     * @param frontendCapabilities 当前浏览器注册的前端动作能力清单
     */
    public ChatRequest(String message,
                       List<Map<String, Object>> history,
                       List<Map<String, Object>> confirmations,
                       String sessionId,
                       String thinkingLevel,
                       UUID authenticatedUserId,
                       String requestId,
                       List<Map<String, Object>> frontendCapabilities) {
        this(message, history, confirmations, sessionId, thinkingLevel,
                authenticatedUserId, requestId, frontendCapabilities, null);
    }

    /**
     * 规范化记录组件并创建服务端内部请求。
     *
     * @param message 本轮用户消息。
     * @param history 已复制为不可变列表的历史消息。
     * @param confirmations 已复制为不可变列表的确认参数。
     * @param sessionId 会话 ID，可为空。
     * @param thinkingLevel 已校验的思考等级；空值转为 auto。
     * @param authenticatedUserId 服务端解析出的 owner UUID，不接受 JSON 注入。
     * @param requestId 服务端生成或复用的请求关联 ID，不接受 JSON 注入。
     * @param frontendCapabilities 当前浏览器注册的前端动作能力清单。
     * @param model 本轮聊天要使用的模型 ID；为空时沿用 owner 的默认模型。
     */
    public ChatRequest {
        history = history == null ? List.of() : List.copyOf(history);
        confirmations = confirmations == null ? List.of() : List.copyOf(confirmations);
        thinkingLevel = thinkingLevel == null || thinkingLevel.isBlank() ? "auto" : thinkingLevel;
        frontendCapabilities = frontendCapabilities == null ? List.of() : frontendCapabilities.stream()
                .filter(java.util.Objects::nonNull)
                .map(java.util.Map::copyOf)
                .toList();
        model = model == null ? "" : model.trim();
    }

    /**
     * 创建只替换会话 ID 的请求副本。
     *
     * @param normalizedSessionId 由会话服务确认归属后的会话 ID。
     * @return 保留其他字段和认证 owner 的新请求记录。
     */
    public ChatRequest withSessionId(String normalizedSessionId) {
        return new ChatRequest(message, history, confirmations, normalizedSessionId, thinkingLevel,
                authenticatedUserId, requestId, frontendCapabilities, model);
    }

    /**
     * 创建只注入认证用户 ID 的请求副本。
     *
     * @param userId 由认证解析器得到的 owner UUID。
     * @return 保留客户端字段和会话 ID、附带服务端 owner 的新请求记录。
     */
    public ChatRequest withAuthenticatedUserId(UUID userId) {
        return new ChatRequest(message, history, confirmations, sessionId, thinkingLevel,
                userId, requestId, frontendCapabilities, model);
    }

    /**
     * 绑定当前 HTTP 聊天流的服务端关联 ID。
     *
     * <p>该字段被 {@code JsonIgnore} 标记，只在 Controller 到 runtime 的内部调用链中
     * 传播，模型和客户端请求体都看不到它。</p>
     *
     * @param correlationId 当前请求使用的关联 ID。
     * @return 保留所有聊天字段并附带关联 ID 的请求副本。
     */
    public ChatRequest withRequestId(String correlationId) {
        return new ChatRequest(message, history, confirmations, sessionId, thinkingLevel,
                authenticatedUserId, correlationId, frontendCapabilities, model);
    }
}
