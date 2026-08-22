/** 前端与后端保持一致的视觉模型名称保守判断。未知模型不允许发送内联图片。 */
const VISION_MODEL_PATTERN = /(?:gpt-(?:4o|4\.1|5)(?:[-.]|$)|o[1-9](?:[-.]|$)|gemini|qwen(?:2\.5|3)?[-_]?vl|llava|internvl|minicpm[-_]?v|pixtral|mistral.*3\.[1-9]|glm[-_]?4v|grok.*vision|kimi.*vl|doubao.*vision|seed.*vision|vision|[-_]vl(?:[-_]|$))/i;
const ANTHROPIC_VISION_MODEL_PATTERN = /(?:^|[-_])claude-(?:3(?:[-.]|$)|4(?:[-.]|$))|(?:^|[-_])claude-(?:opus|sonnet|haiku)-4(?:[-.]|$)/i;

/**
 * 判断当前聊天模型是否可接收图片输入。
 * @param provider Provider 协议标识
 * @param model 模型 ID
 * @param explicit 服务端对当前已保存模型的明确能力结果
 */
export function supportsInlineImages(provider: string | undefined, model: string | undefined, explicit?: boolean) {
  if (explicit !== undefined) return explicit;
  if ((provider || "").toLowerCase().includes("anthropic")) {
    return ANTHROPIC_VISION_MODEL_PATTERN.test(model || "");
  }
  return ANTHROPIC_VISION_MODEL_PATTERN.test(model || "") || VISION_MODEL_PATTERN.test(model || "");
}
