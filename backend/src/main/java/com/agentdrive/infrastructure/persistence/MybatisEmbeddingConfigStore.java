package com.agentdrive.infrastructure.persistence;

import com.agentdrive.infrastructure.EmbeddingConfigStore;
import com.agentdrive.infrastructure.persistence.mapper.EmbeddingConfigMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 将 owner 的 embedding 配置以 JSON 形式存放在偏好表中。
 * <p>API key 密文以 Base64 放进 JSON；读取时 JSON、Base64 任一格式损坏都会抛出状态异常，
 * 防止把坏配置静默当成未配置。</p>
 */
public final class MybatisEmbeddingConfigStore implements EmbeddingConfigStore {
    private final EmbeddingConfigMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * 保存配置 Mapper 和 JSON 映射器。
     * @param mapper 读写 embedding preference JSON 的 Mapper。
     * @param objectMapper 解析和序列化配置对象的 Jackson 映射器。
     */
    public MybatisEmbeddingConfigStore(EmbeddingConfigMapper mapper, ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 读取 owner 的 embedding JSON，并解码其中的 API key 密文。
     * @param userId 配置所属 owner 的 UUID；空值按未配置处理。
     * @return JSON 中的 provider、地址、模型和密文配置；记录不存在或内容为空时为空。
     * @throws IllegalStateException 已保存的 JSON/Base64 内容无法解析时抛出。
     */
    @Override
    public Optional<EmbeddingConfig> find(UUID userId) {
        if (userId == null) return Optional.empty();
        String raw = mapper.select(userId.toString());
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            Map<?, ?> value = objectMapper.readValue(raw, Map.class);
            String provider = string(value.get("provider"));
            String baseUrl = string(value.get("base_url"));
            String model = string(value.get("model"));
            String encrypted = string(value.get("encrypted_api_key"));
            return Optional.of(new EmbeddingConfig(provider, baseUrl, model,
                    encrypted.isBlank() ? null : java.util.Base64.getDecoder().decode(encrypted)));
        } catch (Exception error) {
            throw new IllegalStateException("stored embedding configuration is invalid", error);
        }
    }

    /**
     * 将 embedding 配置序列化为 JSON 并写入 owner preference。
     * @param userId 配置所属 owner 的 UUID，不能为空。
     * @param config 要保存的 provider、地址、模型和 API key 密文，不能为空。
     * @throws IllegalStateException 配置无法序列化时抛出。
     */
    @Override
    public void save(UUID userId, EmbeddingConfig config) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(config, "config must not be null");
        try {
            String encrypted = config.encryptedApiKey() == null ? ""
                    : java.util.Base64.getEncoder().encodeToString(config.encryptedApiKey());
            String value = objectMapper.writeValueAsString(Map.of(
                    "provider", config.provider(),
                    "base_url", config.baseUrl(),
                    "model", config.model(),
                    "encrypted_api_key", encrypted
            ));
            mapper.upsert(userId.toString(), value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("cannot serialize embedding configuration", error);
        }
    }

    /**
     * 将 JSON Map 中可能为 {@code null} 的字段转换为字符串。
     * @param value JSON 字段值。
     * @return 空值对应空字符串，否则返回字段文本。
     */
    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
