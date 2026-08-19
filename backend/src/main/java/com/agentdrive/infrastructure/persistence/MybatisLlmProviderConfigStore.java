package com.agentdrive.infrastructure.persistence;

import com.agentdrive.infrastructure.LlmProviderConfigService;
import com.agentdrive.infrastructure.LlmProviderConfigView;
import com.agentdrive.infrastructure.persistence.mapper.LlmProviderConfigMapper;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 通过 MyBatis 保存和读取 owner-scoped LLM provider 配置。
 * <p>同一适配器同时实现运行时配置端口和设置页服务端口：前者返回密文快照，后者只返回
 * key 是否存在及指纹，避免 API key 明文进入响应。</p>
 */
public final class MybatisLlmProviderConfigStore implements LlmProviderConfigStore, LlmProviderConfigService {
    private final LlmProviderConfigMapper mapper;

    /**
     * 保存 provider 配置 Mapper。
     * @param mapper 读写 provider、地址、模型、密文和指纹列的 Mapper。
     */
    public MybatisLlmProviderConfigStore(LlmProviderConfigMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 读取 owner 的完整 provider 快照。
     * @param userId 配置所属 owner 的 UUID；空值按未找到处理。
     * @return 将数据库列映射为快照；没有配置行时为空。
     */
    @Override
    public Optional<StoredLlmProviderConfig> find(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        Map<String, Object> row = mapper.selectByUserId(userId.toString());
        if (row == null || row.isEmpty()) {
            return Optional.empty();
        }
        Object encrypted = row.get("encrypted_api_key");
        return Optional.of(new StoredLlmProviderConfig(
                string(row.get("provider")),
                string(row.get("base_url")),
                string(row.get("model")),
                encrypted instanceof byte[] bytes ? bytes : null
        ));
    }

    /**
     * 读取设置页可见的脱敏 provider 视图。
     * @param userId 配置所属 owner 的 UUID；空值按未找到处理。
     * @return provider、地址、模型和 key 状态；没有配置行时为空。
     */
    @Override
    public Optional<LlmProviderConfigView> findForOwner(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        Map<String, Object> row = mapper.selectByUserId(userId.toString());
        if (row == null || row.isEmpty()) {
            return Optional.empty();
        }
        Object encrypted = row.get("encrypted_api_key");
        return Optional.of(new LlmProviderConfigView(
                string(row.get("provider")),
                string(row.get("base_url")),
                string(row.get("model")),
                encrypted instanceof byte[] bytes && bytes.length > 0,
                string(row.get("api_key_fingerprint"))
        ));
    }

    /**
     * 提取 owner 的非空 API key 密文，供运行时解密。
     * @param userId 配置所属 owner 的 UUID。
     * @return 密文副本；没有配置行或密文为空时为空。
     */
    @Override
    public Optional<byte[]> encryptedApiKeyForOwner(UUID userId) {
        return find(userId)
                .map(StoredLlmProviderConfig::encryptedApiKey)
                .filter(bytes -> bytes != null && bytes.length > 0);
    }

    /**
     * 通过 Mapper 的 upsert 覆盖 owner 的 provider 配置。
     * @param userId 配置所属 owner 的 UUID，不能为空。
     * @param provider provider 类型，不能为空。
     * @param baseUrl provider 基地址；空值保存为空字符串。
     * @param model 默认模型，不能为空。
     * @param encryptedApiKey API key 密文，不应传入明文。
     * @param apiKeyFingerprint API key 的不可逆指纹。
     * @throws NullPointerException userId、provider 或 model 为空时抛出。
     */
    @Override
    public void saveForOwner(UUID userId,
                             String provider,
                             String baseUrl,
                             String model,
                             byte[] encryptedApiKey,
                             String apiKeyFingerprint) {
        Objects.requireNonNull(userId, "userId must not be null");
        mapper.upsert(
                userId.toString(),
                Objects.requireNonNull(provider, "provider must not be null"),
                baseUrl == null ? "" : baseUrl,
                Objects.requireNonNull(model, "model must not be null"),
                encryptedApiKey,
                apiKeyFingerprint
        );
    }

    /**
     * 把可能为空的 JDBC 列值转换为非空字符串。
     * @param value Mapper 行中的列值。
     * @return {@code null} 转为空字符串，否则返回 {@link String#valueOf(Object)} 结果。
     */
    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
