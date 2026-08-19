package com.agentdrive.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.anthropic.AnthropicChatRequestParameters;

import java.util.List;

/**
 * 为 Anthropic 模型构造 LangChain4j 聊天请求。
 *
 * <p>工具定义始终写入 Anthropic 请求参数；只有显式的 low、medium 或 high
 * 思考等级才开启 thinking，并映射到对应的 token 预算。{@code auto} 不发送
 * thinking 参数，以兼容不支持该能力的模型。</p>
 */
public final class AnthropicChatRequestFactory implements ChatRequestFactory {
    /**
     * 将消息、工具和思考等级转换为 Anthropic 请求。
     *
     * <p>显式思考等级会同时设置 thinking 类型、预算以及收发 thinking 内容的
     * 开关；方法本身只构造请求对象，不发起网络调用。</p>
     *
     * @param messages 按对话顺序发送给模型的消息
     * @param toolSpecifications 本轮允许模型调用的工具定义
     * @param thinkingLevel 前端请求的思考等级；{@code AUTO} 表示不显式开启 thinking
     * @return 包含消息和 Anthropic 参数的聊天请求
     */
    @Override
    public dev.langchain4j.model.chat.request.ChatRequest create(
            List<ChatMessage> messages,
            List<ToolSpecification> toolSpecifications,
            ThinkingLevel thinkingLevel
    ) {
        AnthropicChatRequestParameters.Builder parameters = AnthropicChatRequestParameters.builder();
        parameters.toolSpecifications(toolSpecifications);
        if (thinkingLevel.providerValue() != null) {
            parameters.thinkingType("enabled")
                    .thinkingBudgetTokens(budgetFor(thinkingLevel))
                    .sendThinking(true)
                    .returnThinking(true);
        }
        return dev.langchain4j.model.chat.request.ChatRequest.builder()
                .messages(messages)
                .parameters(parameters.build())
                .build();
    }

    /**
     * 把思考等级转换为 Anthropic 的 thinking token 预算。
     *
     * @param level 必须是显式的 low、medium 或 high 等级
     * @return 对应的 1024、4096 或 8192 token 预算
     * @throws IllegalArgumentException 当调用方传入 {@code AUTO} 时抛出，因为
     *                                  auto 不应发送显式预算
     */
    private int budgetFor(ThinkingLevel level) {
        return switch (level) {
            case LOW -> 1024;
            case MEDIUM -> 4096;
            case HIGH -> 8192;
            case AUTO -> throw new IllegalArgumentException("auto has no explicit Anthropic budget");
        };
    }
}
