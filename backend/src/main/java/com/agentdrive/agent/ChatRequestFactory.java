package com.agentdrive.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 将统一的聊天输入转换为具体 Provider 的 LangChain4j 请求。
 *
 * <p>实现决定工具定义和 thinking_level 如何落到 Provider 参数；调用只负责构造
 * 请求，不应在此接口中发起网络请求或持久化会话。</p>
 */
@FunctionalInterface
public interface ChatRequestFactory {
    /**
     * 构造一次聊天请求。
     *
     * @param messages 对话历史和当前输入，保持发送顺序
     * @param toolSpecifications 当前轮可用的工具 schema
     * @param thinkingLevel 当前请求的思考等级
     * @return Provider 可消费的聊天请求
     */
    dev.langchain4j.model.chat.request.ChatRequest create(
            List<ChatMessage> messages,
            List<ToolSpecification> toolSpecifications,
            ThinkingLevel thinkingLevel
    );
}
