package com.agentdrive.infrastructure;

import com.agentdrive.vision.VisionConfigStore;
import com.agentdrive.vision.VisionRuntimeConfig;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 从 owner preference 中读取视觉配置，并在进入视觉 HTTP 客户端前解密 API key。
 */
@Component
@Profile({"java-files", "java-auth", "java-chat"})
public final class DatabaseVisionRuntimeConfig implements VisionRuntimeConfig {
    private final VisionConfigStore configs;
    private final LlmApiKeyCipher keyCipher;

    /**
     * 创建视觉运行时配置适配器。
     * @param configs 视觉配置持久化端口。
     * @param keyCipher API key AES-GCM 解密器。
     */
    public DatabaseVisionRuntimeConfig(VisionConfigStore configs, LlmApiKeyCipher keyCipher) {
        this.configs = configs;
        this.keyCipher = keyCipher;
    }

    /**
     * 读取并解密当前 owner 的视觉配置。
     * @param userId 配置所属 owner UUID。
     * @return 视觉请求配置；未配置时为空。
     */
    @Override
    public Optional<Config> find(UUID userId) {
        return configs.find(userId).map(config -> new Config(
                config.provider(), config.baseUrl(), config.model(), keyCipher.decrypt(config.encryptedApiKey())));
    }
}
