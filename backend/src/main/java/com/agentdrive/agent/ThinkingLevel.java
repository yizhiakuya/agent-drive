package com.agentdrive.agent;

/**
 * 前端思考等级与 Provider 参数值的映射。
 *
 * <p>{@code AUTO} 保留为兼容默认值，并故意没有 providerValue；显式等级才会被
 * OpenAI 映射为 reasoning effort、被 Anthropic 映射为 thinking 预算。</p>
 */
public enum ThinkingLevel {
    AUTO("auto", null),
    LOW("low", "low"),
    MEDIUM("medium", "medium"),
    HIGH("high", "high");

    private final String wireValue;
    private final String providerValue;

    /**
     * 保存前端传输值和 Provider 专用值。
     * @param wireValue API 请求中的字符串值
     * @param providerValue Provider 参数值；AUTO 为 null
     */
    ThinkingLevel(String wireValue, String providerValue) {
        this.wireValue = wireValue;
        this.providerValue = providerValue;
    }

    /** 返回 API 使用的思考等级字符串。 @return auto、low、medium 或 high */
    public String wireValue() {
        return wireValue;
    }

    /** 返回 Provider 使用的参数值。 @return 显式等级值；AUTO 返回 null */
    public String providerValue() {
        return providerValue;
    }

    /**
     * 解析 API 中的思考等级，空值按兼容默认值 AUTO 处理。
     * @param value API 请求中的等级字符串，区分大小写
     * @return 对应的等级
     * @throws IllegalArgumentException value 不是四个支持值之一时抛出
     */
    public static ThinkingLevel from(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        for (ThinkingLevel level : values()) {
            if (level.wireValue.equals(value)) {
                return level;
            }
        }
        throw new IllegalArgumentException("thinking_level must be auto, low, medium, or high");
    }
}
