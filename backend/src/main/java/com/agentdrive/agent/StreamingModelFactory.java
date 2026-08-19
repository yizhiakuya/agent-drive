package com.agentdrive.agent;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

/**
 * 根据 Provider 配置创建流式聊天模型及其请求参数工厂。
 *
 * <p>OpenAI 和 OpenAI-compatible 共用 OpenAI SDK，Anthropic 使用 Anthropic SDK；
 * OpenAI 流式模型始终开启 thinking 回调，否则兼容网关的 reasoning_content 会被
 * LangChain4j 丢弃。</p>
 */
public final class StreamingModelFactory {
    /**
     * 按配置类型创建对应的流式模型。
     * @param config Provider API key、地址、模型和协议配置
     * @return 已配置但尚未发起请求的流式聊天模型
     */
    public StreamingChatModel create(LlmProviderConfig config) {
        return switch (config.type()) {
            case OPENAI, OPENAI_COMPATIBLE -> openAi(config);
            case ANTHROPIC -> anthropic(config);
        };
    }

    /**
     * 选择与 Provider 协议匹配的请求参数工厂。
     * @param config Provider 类型配置
     * @return OpenAI、兼容协议或 Anthropic 对应的请求工厂
     */
    public ChatRequestFactory requestFactory(LlmProviderConfig config) {
        return switch (config.type()) {
            case OPENAI, OPENAI_COMPATIBLE -> new OpenAiChatRequestFactory();
            case ANTHROPIC -> new AnthropicChatRequestFactory();
        };
    }

    /**
     * 构造 OpenAI SDK 的流式模型，并按配置覆盖默认 base URL。
     * @param config OpenAI 或兼容协议配置
     * @return 开启 reasoning 回调的 OpenAI 流式模型
     */
    private StreamingChatModel openAi(LlmProviderConfig config) {
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder =
                OpenAiStreamingChatModel.builder()
                        .apiKey(config.apiKey())
                        .modelName(config.modelName())
                        // langchain4j 只在模型构建期 returnThinking=true 时才会把
                        // chat/completions 流中的 reasoning_content delta 转成
                        // onPartialThinking / AiMessage.thinking()；默认 false 会静默丢弃，
                        // 导致 OpenAI/兼容网关（DeepSeek、Qwen、o 系等）的思考过程不显示。
                        .returnThinking(true);
        if (!config.baseUrl().isBlank()) {
            builder.baseUrl(config.baseUrl());
        }
        return builder.build();
    }

    /**
     * 构造 Anthropic SDK 的流式模型，并按配置覆盖默认 base URL。
     * @param config Anthropic Provider 配置
     * @return Anthropic 流式聊天模型
     */
    private StreamingChatModel anthropic(LlmProviderConfig config) {
        AnthropicStreamingChatModel.AnthropicStreamingChatModelBuilder builder =
                AnthropicStreamingChatModel.builder()
                        .apiKey(config.apiKey())
                        .modelName(config.modelName());
        if (!config.baseUrl().isBlank()) {
            builder.baseUrl(config.baseUrl());
        }
        return builder.build();
    }
}
