package com.agentdrive.agent;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 判断当前聊天模型是否具备图片输入能力。
 *
 * <p>Provider 的 {@code /models} 响应能力字段并不统一，因此这里提供保守的
 * provider + 模型名兜底规则：已知视觉模型直接允许，未知模型默认拒绝。调用方若
 * 已拿到 Provider 明确的能力字段，应优先使用该字段。该判断只控制本轮内联图片
 * 是否进入模型请求，不会改变普通文本聊天或持久文件附件能力。</p>
 */
public final class ChatModelCapabilities {
    private static final Pattern VISION_MODEL = Pattern.compile(
            "(?:gpt-(?:4o|4\\.1|5(?:[-.]|$))|o[1-9](?:[-.]|$)|gemini|"
                    + "qwen(?:2\\.5|3)?[-_]?vl|llava|internvl|minicpm[-_]?v|pixtral|"
                    + "mistral.*3\\.[1-9]|glm[-_]?4v|grok.*vision|kimi.*vl|"
                    + "doubao.*vision|seed.*vision|vision|[-_]vl(?:[-_]|$))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ANTHROPIC_VISION_MODEL = Pattern.compile(
            "(?:^|[-_])claude-(?:3(?:[-.]|$)|4(?:[-.]|$))"
                    + "|(?:^|[-_])claude-(?:opus|sonnet|haiku)-4(?:[-.]|$)",
            Pattern.CASE_INSENSITIVE);

    private ChatModelCapabilities() {
    }

    /**
     * 判断 provider/model 是否可接收图片输入。
     *
     * @param provider Provider 类型或兼容协议标识。
     * @param model 模型 ID。
     * @return 已知支持图片输入时为 {@code true}，未知或明显文本模型时为 {@code false}。
     */
    public static boolean supportsImages(String provider, String model) {
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        String normalizedModel = model == null ? "" : model.trim();
        if (normalizedProvider.contains("anthropic")) {
            return !normalizedModel.isBlank() && ANTHROPIC_VISION_MODEL.matcher(normalizedModel).find();
        }
        return !normalizedModel.isBlank()
                && (ANTHROPIC_VISION_MODEL.matcher(normalizedModel).find()
                || VISION_MODEL.matcher(normalizedModel).find());
    }
}
