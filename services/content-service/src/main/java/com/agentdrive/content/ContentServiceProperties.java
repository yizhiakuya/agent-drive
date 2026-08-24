package com.agentdrive.content;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Content Service 的 Provider、内部认证和资源上限配置。 */
@ConfigurationProperties(prefix = "content")
public record ContentServiceProperties(
        String internalToken,
        String provider,
        String baseUrl,
        String model,
        String apiKey,
        Integer maxImages,
        Long maxImageBytes,
        Integer maxResponseBytes,
        Long maxRequestBytes
) {
    /** 兼容只设置响应上限的测试/本地调用构造器。 */
    public ContentServiceProperties(String internalToken, String provider, String baseUrl, String model,
                                    String apiKey, Integer maxImages, Long maxImageBytes, Integer maxResponseBytes) {
        this(internalToken, provider, baseUrl, model, apiKey, maxImages, maxImageBytes, maxResponseBytes, null);
    }

    /** 在配置边界固定默认值并清理文本字段。 */
    @ConstructorBinding
    public ContentServiceProperties {
        internalToken = clean(internalToken);
        provider = clean(provider).isBlank() ? "openai_compat" : clean(provider);
        baseUrl = clean(baseUrl);
        model = clean(model);
        apiKey = clean(apiKey);
        maxImages = maxImages == null ? 4 : Math.max(1, Math.min(maxImages, 16));
        maxImageBytes = maxImageBytes == null ? 10L * 1024 * 1024 : Math.max(1, Math.min(maxImageBytes, 50L * 1024 * 1024));
        maxResponseBytes = maxResponseBytes == null ? 2 * 1024 * 1024 : Math.max(1024, Math.min(maxResponseBytes, 8 * 1024 * 1024));
        maxRequestBytes = maxRequestBytes == null ? 80L * 1024 * 1024
                : Math.max(1024, Math.min(maxRequestBytes, 320L * 1024 * 1024));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
