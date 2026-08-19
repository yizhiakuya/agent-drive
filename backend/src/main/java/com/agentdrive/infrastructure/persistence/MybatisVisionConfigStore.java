package com.agentdrive.infrastructure.persistence;

import com.agentdrive.infrastructure.persistence.mapper.VisionConfigMapper;
import com.agentdrive.vision.VisionConfigStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 把视觉模型配置以 owner preference JSON 持久化。
 *
 * <p>JSON 中只保存 Base64 编码的 AES-GCM 密文；配置损坏会直接失败，而不会静默回退成
 * 未配置，避免 Worker 在错误模型或错误凭据下运行。</p>
 */
public final class MybatisVisionConfigStore implements VisionConfigStore {
    private final VisionConfigMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * 创建视觉配置存储。
     * @param mapper 读写视觉 preference JSON 的 Mapper。
     * @param objectMapper 编解码 JSON 配置的 Jackson mapper。
     */
    public MybatisVisionConfigStore(VisionConfigMapper mapper, ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 读取并校验 owner 的视觉配置。
     * @param userId 配置所属 owner UUID。
     * @return 解码后的 provider、地址、模型和密文 key。
     */
    @Override
    public Optional<VisionConfig> find(UUID userId) {
        if (userId == null) return Optional.empty();
        String raw = mapper.select(userId.toString());
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            Map<?, ?> value = objectMapper.readValue(raw, Map.class);
            String provider = string(value.get("provider"));
            String baseUrl = string(value.get("base_url"));
            String model = string(value.get("model"));
            String encrypted = string(value.get("encrypted_api_key"));
            return Optional.of(new VisionConfig(provider, baseUrl, model,
                    encrypted.isBlank() ? null : Base64.getDecoder().decode(encrypted)));
        } catch (Exception error) {
            throw new IllegalStateException("stored vision configuration is invalid", error);
        }
    }

    /**
     * 序列化并保存 owner 的视觉配置。
     * @param userId 配置所属 owner UUID。
     * @param config 要保存的视觉配置，API key 必须已经加密。
     */
    @Override
    public void save(UUID userId, VisionConfig config) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(config, "config must not be null");
        try {
            String encrypted = config.encryptedApiKey() == null ? ""
                    : Base64.getEncoder().encodeToString(config.encryptedApiKey());
            String value = objectMapper.writeValueAsString(Map.of(
                    "provider", config.provider(),
                    "base_url", config.baseUrl(),
                    "model", config.model(),
                    "encrypted_api_key", encrypted
            ));
            mapper.upsert(userId.toString(), value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("cannot serialize vision configuration", error);
        }
    }

    /**
     * 把 JSON 字段转换为非空字符串。
     * @param value JSON 字段值。
     * @return null 转为空字符串的字段文本。
     */
    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
