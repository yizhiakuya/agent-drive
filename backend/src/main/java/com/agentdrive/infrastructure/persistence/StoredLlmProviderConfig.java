package com.agentdrive.infrastructure.persistence;

import java.util.Arrays;
import java.util.Objects;

/**
 * 数据库中 LLM provider 配置的不可变快照。
 * <p>构造和访问都会复制 API key 密文字节，避免记录暴露可变数组；provider/model 必须存在，
 * base URL 可为空。</p>
 * @param provider provider 类型字符串。
 * @param baseUrl provider 基地址；未配置时为空字符串。
 * @param model 默认模型名。
 * @param encryptedApiKey AES-GCM API key 密文；未配置时为 {@code null}。
 */
public record StoredLlmProviderConfig(
        String provider,
        String baseUrl,
        String model,
        byte[] encryptedApiKey
) {
    /**
     * 校验必填 provider/model，并复制密文字节以保持值对象不可变。
     * @param provider provider 类型字符串，不能为 {@code null}。
     * @param baseUrl provider 基地址；{@code null} 会转换为空字符串。
     * @param model 默认模型名，不能为 {@code null}。
     * @param encryptedApiKey 要保存的密文数组；数组会被复制。
     * @throws NullPointerException provider 或 model 为 {@code null} 时抛出。
     */
    public StoredLlmProviderConfig {
        provider = Objects.requireNonNull(provider, "provider must not be null");
        baseUrl = baseUrl == null ? "" : baseUrl;
        model = Objects.requireNonNull(model, "model must not be null");
        encryptedApiKey = encryptedApiKey == null ? null : encryptedApiKey.clone();
    }

    /**
     * 返回 API key 密文的防御性副本。
     * @return 密文字节副本；未配置 key 时为 {@code null}。
     */
    @Override
    public byte[] encryptedApiKey() {
        return encryptedApiKey == null ? null : encryptedApiKey.clone();
    }

    /**
     * 比较 provider、地址、模型以及密文内容；密文按字节而不是数组引用比较。
     * @param other 待比较的对象。
     * @return 另一个对象是相同配置且密文内容相同的 {@code StoredLlmProviderConfig} 时为 {@code true}。
     */
    @Override
    public boolean equals(Object other) {
        if (!(other instanceof StoredLlmProviderConfig that)) {
            return false;
        }
        return Objects.equals(provider, that.provider)
                && Objects.equals(baseUrl, that.baseUrl)
                && Objects.equals(model, that.model)
                && Arrays.equals(encryptedApiKey, that.encryptedApiKey);
    }

    /**
     * 根据所有配置字段计算与 {@link #equals(Object)} 一致的哈希值。
     * @return 包含密文字节内容的哈希值。
     */
    @Override
    public int hashCode() {
        return Objects.hash(provider, baseUrl, model, Arrays.hashCode(encryptedApiKey));
    }
}
