package com.agentdrive.agent;

import java.util.Objects;
import java.util.UUID;

/**
 * 为所有用户返回同一个已构建的聊天运行时。
 *
 * <p>适用于启动时固定 Provider 的场景；它不根据 userId 查配置，也不执行用户级
 * 权限判断。需要 owner-scoped Provider 配置时应使用动态解析器实现。</p>
 */
public final class FixedProviderRuntimeResolver implements ProviderRuntimeResolver {
    private final ConfiguredChatModel configured;

    /**
     * 保存不可变的模型和请求工厂组合。
     * @param configured 已创建的聊天运行时
     * @throws NullPointerException configured 为空时抛出
     */
    public FixedProviderRuntimeResolver(ConfiguredChatModel configured) {
        this.configured = Objects.requireNonNull(configured, "configured model must not be null");
    }

    /**
     * 返回启动时绑定的运行时，忽略用户 ID。
     * @param userId 当前用户 ID；固定解析策略不使用它
     * @return 构造器保存的模型与请求工厂
     */
    @Override
    public ConfiguredChatModel resolve(UUID userId) {
        return configured;
    }
}
