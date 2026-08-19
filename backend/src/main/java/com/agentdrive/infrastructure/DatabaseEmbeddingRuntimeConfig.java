package com.agentdrive.infrastructure;

import com.agentdrive.index.EmbeddingRuntimeConfig;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 从 owner-scoped 持久化配置构造向量运行时配置。
 * <p>存储层只保存加密 API key；本适配器在返回给 embedding 管线前解密，未配置用户返回空值。</p>
 */
@Component
@Profile({"java-files", "java-auth", "java-chat"})
public final class DatabaseEmbeddingRuntimeConfig implements EmbeddingRuntimeConfig {
    private final EmbeddingConfigStore configs;
    private final LlmApiKeyCipher keyCipher;

    /**
     * 保存配置读取器和 API key 解密器。
     * @param configs owner-scoped embedding 配置存储。
     * @param keyCipher 解密存储层密文的 AES-GCM 服务。
     */
    public DatabaseEmbeddingRuntimeConfig(EmbeddingConfigStore configs, LlmApiKeyCipher keyCipher) {
        this.configs = configs;
        this.keyCipher = keyCipher;
    }

    /**
     * 读取用户的 embedding 配置，并把密文 API key 转为运行时明文。
     * @param userId 配置所属 owner 的 UUID。
     * @return 配置存在时返回 provider、地址、模型和明文 key，否则为空。
     * @throws IllegalArgumentException 持久化密文格式错误或认证标签校验失败时由解密器抛出。
     */
    @Override
    public Optional<Config> find(UUID userId) {
        return configs.find(userId).map(config -> new Config(
                config.provider(), config.baseUrl(), config.model(), keyCipher.decrypt(config.encryptedApiKey())));
    }
}
