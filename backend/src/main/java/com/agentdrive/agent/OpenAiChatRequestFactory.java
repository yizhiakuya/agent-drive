package com.agentdrive.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

import java.util.List;

/**
 * 为 OpenAI 和 OpenAI-compatible 模型构造聊天请求。
 *
 * <p>工具 schema 放在 OpenAI 参数对象中；只有非 AUTO 等级才设置 reasoning effort，
 * 从而不向普通模型发送不兼容的 thinking 参数。</p>
 */
public final class OpenAiChatRequestFactory implements ChatRequestFactory {
    /**
     * 将对话消息、工具和思考等级映射为 OpenAI 请求参数。
     * @param messages 按顺序发送的对话消息
     * @param toolSpecifications 当前轮允许模型调用的工具
     * @param thinkingLevel AUTO 或显式 reasoning effort 等级
     * @return 不执行网络请求的 LangChain4j 聊天请求
     */
    @Override
    public dev.langchain4j.model.chat.request.ChatRequest create(
            List<ChatMessage> messages,
            List<ToolSpecification> toolSpecifications,
            ThinkingLevel thinkingLevel
    ) {
        OpenAiChatRequestParameters.Builder parameters = OpenAiChatRequestParameters.builder();
        parameters.toolSpecifications(toolSpecifications);
        // Sub2API 的 OpenAI-compatible 多工具流在一次响应返回多个 tool call 时，
        // 可能在上游已经产生首字后迟迟不关闭 SSE；串行工具调用避免把尾部等待误算成模型/工具耗时。
        parameters.parallelToolCalls(false);
        if (thinkingLevel.providerValue() != null) {
            parameters.reasoningEffort(thinkingLevel.providerValue());
        }
        return dev.langchain4j.model.chat.request.ChatRequest.builder()
                .messages(messages)
                .parameters(parameters.build())
                .build();
    }
}
