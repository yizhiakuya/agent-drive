package com.agentdrive.agent;

import dev.langchain4j.model.chat.StreamingChatModel;

import java.util.Objects;

/**
 * 将已创建的流式聊天模型与对应请求工厂绑定。
 *
 * <p>运行时解析器返回此记录，使 Agent 循环既能调用同一个模型，又能使用与
 * Provider 匹配的 thinking/tool 参数转换规则。</p>
 */
public record ConfiguredChatModel(
        StreamingChatModel model,
        ChatRequestFactory requestFactory,
        String provider,
        String modelName
) {
    /**
     * 兼容固定模型构造方式，并使用实现类名作为诊断标签。
     *
     * @param model 已配置的流式聊天模型
     * @param requestFactory 将统一聊天输入转换为该模型请求的工厂
     */
    public ConfiguredChatModel(StreamingChatModel model, ChatRequestFactory requestFactory) {
        this(model, requestFactory,
                model == null ? "unknown" : model.getClass().getSimpleName(),
                model == null ? "unknown" : model.getClass().getSimpleName());
    }

    /**
     * 校验并保存模型及其请求构造器。
     * @param model 已配置的流式聊天模型
     * @param requestFactory 将统一聊天输入转换为该模型请求的工厂
     * @param provider 已脱敏的 Provider 类型或诊断标签
     * @param modelName 已脱敏的模型名称；不应包含 API key
     * @throws NullPointerException 任一依赖为空时抛出
     */
    public ConfiguredChatModel {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(requestFactory, "requestFactory must not be null");
        provider = provider == null || provider.isBlank() ? "unknown" : provider;
        modelName = modelName == null || modelName.isBlank() ? "unknown" : modelName;
    }
}
