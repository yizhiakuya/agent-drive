package com.agentdrive.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

/**
 * LlmProviderConfigStore 是一个持久化访问接口，负责封装该模块的核心协作职责。
 * 它只处理与自身边界相关的协作，不承担上层流程编排或跨模块状态管理。
 */
public interface LlmProviderConfigStore {
    /**
     * 按用户 ID 查询已保存的 LLM 提供商配置；未配置时返回 {@link Optional#empty()}，不创建或修改配置。
     *
     * @param userId 数据所属用户的唯一标识
     * @return 该用户的已保存配置，未找到时为空
     */
    Optional<StoredLlmProviderConfig> find(UUID userId);
}
