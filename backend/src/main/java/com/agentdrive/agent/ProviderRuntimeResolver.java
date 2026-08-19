package com.agentdrive.agent;

import java.util.UUID;

/**
 * 按用户解析 Agent 使用的聊天模型和请求工厂。
 *
 * <p>实现可以返回固定模型，也可以从 owner-scoped 配置创建不同 Provider；解析失败
 * 应向上层保留明确异常，不得伪造未配置的模型。</p>
 */
@FunctionalInterface
public interface ProviderRuntimeResolver {
    /**
     * 为用户解析一次可用的聊天运行时。
     * @param userId 当前 owner 用户 ID
     * @return 已绑定模型和 Provider 请求工厂的运行时
     */
    ConfiguredChatModel resolve(UUID userId);
}
