package com.agentdrive.infrastructure;

/**
 * 返回给配置接口的 LLM provider 脱敏视图。
 * <p>该值对象只表达 key 是否存在及其指纹，绝不携带可恢复的 API key 内容。</p>
 * @param provider provider 类型标识。
 * @param baseUrl provider 请求基地址。
 * @param model 默认模型名。
 * @param apiKeyConfigured 是否存在非空 API key 密文。
 * @param apiKeyFingerprint API key 的不可逆指纹。
 */
public record LlmProviderConfigView(
        String provider,
        String baseUrl,
        String model,
        boolean apiKeyConfigured,
        String apiKeyFingerprint
) {
    /**
     * 创建不带 key 指纹的兼容视图。
     * @param provider provider 类型标识。
     * @param baseUrl provider 请求基地址。
     * @param model 默认模型名。
     * @param apiKeyConfigured 是否已配置 API key。
     */
    public LlmProviderConfigView(String provider,
                                 String baseUrl,
                                 String model,
                                 boolean apiKeyConfigured) {
        this(provider, baseUrl, model, apiKeyConfigured, "");
    }
}
