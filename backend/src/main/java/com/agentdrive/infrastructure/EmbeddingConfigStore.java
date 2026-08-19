package com.agentdrive.infrastructure;

import java.util.Optional;
import java.util.UUID;

/**
 * 访问 owner-scoped embedding 配置的持久化端口。
 * <p>实现保存 provider、base URL、模型和加密后的 API key；读取不到配置时返回空值。</p>
 */
public interface EmbeddingConfigStore {
    /**
     * 查询某个 owner 的 embedding 配置。
     * @param userId 配置所属 owner 的 UUID。
     * @return 找到的配置；未配置或 owner 不存在时为空。
     */
    Optional<EmbeddingConfig> find(UUID userId);

    /**
     * 创建或覆盖某个 owner 的 embedding 配置。
     * @param userId 配置所属 owner 的 UUID。
     * @param config 要持久化的 provider、地址、模型和 API key 密文；明文 key 不应进入此接口。
     */
    void save(UUID userId, EmbeddingConfig config);

    /**
     * embedding 配置在持久化端口上的值对象。
     * @param provider embedding 服务商标识。
     * @param baseUrl 服务商 API 基地址。
     * @param model 使用的 embedding 模型名。
     * @param encryptedApiKey API key 的密文；未配置 key 时为 {@code null}。
     */
    record EmbeddingConfig(String provider, String baseUrl, String model, byte[] encryptedApiKey) {
    }
}
