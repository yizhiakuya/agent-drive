package com.agentdrive.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 绑定 {@code app.*} 配置并提供运行时使用的规范化值。
 * <p>记录组件在绑定时补齐空字符串、默认数据目录、聊天步数和上传大小；
 * 两个密钥仍以 Base64 文本保存，只有密钥访问方法被调用时才校验并解码。</p>
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String mode,
        Boolean cookieSecure,
        String confirmationKey,
        String llmConfigEncryptionKey,
        String systemPrompt,
        Integer maxChatSteps,
        String dataDir,
        Integer maxUploadMb
) {
    /**
     * 创建面向最小启动配置的属性对象，其余配置使用完整绑定构造器的默认值。
     * @param mode 运行模式；为空时由 {@link #normalizedMode()} 解释为 {@code api}。
     * @param cookieSecure 是否为 Cookie 设置 Secure 标志。
     */
    public AppProperties(String mode, Boolean cookieSecure) {
        this(mode, cookieSecure, "", "", "", 100, "data", 300);
    }

    /**
     * 绑定完整的 {@code app.*} 配置并在边界处固定默认值。
     * <p>密钥去除首尾空白；提示词只把 {@code null} 转为空字符串；数值默认值分别为 100、
     * {@code data} 和 300。</p>
     * @param mode 运行模式，例如 {@code api} 或 {@code worker}。
     * @param cookieSecure 是否只通过 HTTPS 发送 Cookie。
     * @param confirmationKey 用于确认重放签名的 Base64 密钥文本。
     * @param llmConfigEncryptionKey 用于加密模型 API key 的 Base64 密钥文本。
     * @param systemPrompt Agent 使用的附加系统提示词。
     * @param maxChatSteps 单次 Agent 对话允许执行的最大步数；空值默认 100。
     * @param dataDir 文件和迁移数据根目录；空值默认 {@code data}。
     * @param maxUploadMb 单个上传的 MB 上限；空值默认 300。
     */
    @ConstructorBinding
    public AppProperties {
        confirmationKey = confirmationKey == null ? "" : confirmationKey.trim();
        llmConfigEncryptionKey = llmConfigEncryptionKey == null ? "" : llmConfigEncryptionKey.trim();
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        maxChatSteps = maxChatSteps == null ? 100 : maxChatSteps;
        dataDir = dataDir == null || dataDir.isBlank() ? "data" : dataDir.trim();
        maxUploadMb = maxUploadMb == null ? 300 : maxUploadMb;
    }

    /**
     * 返回有效的运行模式。
     * @return 配置中的非空模式；模式未配置时返回 {@code api}。
     */
    public String normalizedMode() {
        return mode == null || mode.isBlank() ? "api" : mode;
    }

    /**
     * 将可空的 Cookie 安全开关转换为非空布尔值。
     * @return 仅当绑定值为 {@link Boolean#TRUE} 时返回 {@code true}。
     */
    public boolean secureCookies() {
        return Boolean.TRUE.equals(cookieSecure);
    }

    /**
     * 解码确认签名密钥。
     * @return 从 {@code app.confirmation-key} 解码得到的 32 字节密钥。
     * @throws IllegalArgumentException 密钥为空、不是合法 Base64 或解码后不是 32 字节时抛出。
     */
    public byte[] confirmationSigningKey() {
        return SecretMaterial.base64Key(confirmationKey, "app.confirmation-key");
    }

    /**
     * 解码数据库中 LLM 配置 API key 所用的加密密钥。
     * @return 从 {@code app.llm-config-encryption-key} 解码得到的 32 字节密钥。
     * @throws IllegalArgumentException 密钥为空、不是合法 Base64 或解码后不是 32 字节时抛出。
     */
    public byte[] llmConfigKey() {
        return SecretMaterial.base64Key(llmConfigEncryptionKey, "app.llm-config-encryption-key");
    }

    /**
     * 把 MB 配置转换成文件上传服务使用的字节上限。
     * @return {@code maxUploadMb} 与 1 MB 中较大者乘以 1024 的平方。
     */
    public long maxUploadBytes() {
        return Math.max(1, maxUploadMb) * 1024L * 1024L;
    }
}
