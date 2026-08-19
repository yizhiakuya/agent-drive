package com.agentdrive.vision;

import java.util.Optional;
import java.util.UUID;

/**
 * 访问 owner-scoped 视觉模型配置的视觉模块端口。
 *
 * <p>视觉业务只依赖这个持久化抽象；数据库、JSON 和 MyBatis 细节由基础设施模块实现，
 * 因而不会把视觉配置服务反向耦合到具体存储技术。</p>
 */
public interface VisionConfigStore {
    /**
     * 查询指定 owner 当前的视觉模型配置。
     *
     * @param userId 配置所属 owner 的 UUID。
     * @return 已保存配置；未配置时为空。
     */
    Optional<VisionConfig> find(UUID userId);

    /**
     * 保存或覆盖指定 owner 的视觉模型配置。
     *
     * @param userId 配置所属 owner 的 UUID。
     * @param config provider、地址、模型和加密 key。
     */
    void save(UUID userId, VisionConfig config);

    /**
     * 视觉模型配置值对象。
     *
     * @param provider 视觉模型协议标识，目前为 {@code openai_compat}。
     * @param baseUrl OpenAI 兼容 API 的基础地址。
     * @param model 视觉模型 ID。
     * @param encryptedApiKey AES-GCM 加密的 API key。
     */
    record VisionConfig(String provider, String baseUrl, String model, byte[] encryptedApiKey) {
    }
}
