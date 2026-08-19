package com.agentdrive.agent;

import java.util.Locale;
import java.util.Objects;

/**
 * 描述一次 LLM Provider 运行时所需的连接配置。
 *
 * <p>构造时会修剪 API key、地址和模型名，并拒绝空模型名。API key 只供 Provider
 * 工厂使用，不应写入日志、会话或工具输出；baseUrl 为空时由具体 SDK 使用默认地址。</p>
 */
public record LlmProviderConfig(
        ProviderType type,
        String apiKey,
        String baseUrl,
        String modelName
) {
    /**
     * 规范化并校验 Provider 配置。
     * @param type Provider 类型
     * @param apiKey 访问 Provider 的密钥；null 按空字符串处理
     * @param baseUrl Provider 基础地址；空值表示使用 SDK 默认地址
     * @param modelName 模型标识，不能为空
     * @throws NullPointerException type 为空时抛出
     * @throws IllegalArgumentException modelName 为空时抛出
     */
    public LlmProviderConfig {
        type = Objects.requireNonNull(type, "type must not be null");
        apiKey = apiKey == null ? "" : apiKey.trim();
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        modelName = modelName == null ? "" : modelName.trim();
        if (modelName.isBlank()) {
            throw new IllegalArgumentException("modelName must not be blank");
        }
    }

    /**
     * 支持的 Provider 协议类型。
     */
    public enum ProviderType {
        OPENAI,
        OPENAI_COMPATIBLE,
        ANTHROPIC;

        /**
         * 将配置或表单中的协议字符串转换为枚举值。
         *
         * <p>转换会忽略首尾空白、大小写和连字符，并兼容 {@code OPENAI_COMPAT}、
         * {@code OPENAI_RESPONSES} 两个历史别名。</p>
         * @param value 协议名称
         * @return 规范化后的 Provider 类型
         * @throws IllegalArgumentException value 为空或不是支持的协议时抛出
         */
        public static ProviderType from(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("provider type must not be blank");
            }
            String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            if ("OPENAI_COMPAT".equals(normalized)
                    || "OPENAI_RESPONSES".equals(normalized)) {
                normalized = "OPENAI_COMPATIBLE";
            }
            return valueOf(normalized);
        }
    }
}
