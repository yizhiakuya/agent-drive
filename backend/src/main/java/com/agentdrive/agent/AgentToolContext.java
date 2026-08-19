package com.agentdrive.agent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一次 Agent 工具调用的服务端上下文。
 *
 * <p>owner 和 request ID 由 runtime 注入，模型不能通过工具 schema 提供；前端能力清单
 * 来自当前浏览器，只用于 {@code frontend_api} 的动作 allowlist，不会改变后端认证或
 * operation catalog。</p>
 *
 * @param authenticatedUserId 当前认证用户
 * @param requestId 当前聊天流关联 ID
 * @param frontendCapabilities 当前浏览器声明的前端动作能力
 */
public record AgentToolContext(
        UUID authenticatedUserId,
        String requestId,
        List<Map<String, Object>> frontendCapabilities
) {
    /**
     * 规范化客户端能力清单，避免工具看到可变请求对象。
     */
    public AgentToolContext {
        frontendCapabilities = frontendCapabilities == null ? List.of() : frontendCapabilities.stream()
                .filter(java.util.Objects::nonNull)
                .map(Map::copyOf)
                .toList();
    }
}
