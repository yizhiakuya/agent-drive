package com.agentdrive.agent;

import java.util.Map;

/**
 * Agent 工具适配器的统一执行契约。
 *
 * <p>工具的 LangChain4j schema 仍由各适配器上的 {@code @Tool} 方法声明；本接口负责
 * 让 Agent runtime 用同一方式定位工具、传递请求上下文、执行原始 JSON 参数和接收
 * 可选的客户端事件。后端 API、浏览器动作以及未来的其他通道都通过这个边界接入，
 * runtime 不需要知道每种工具的业务细节。</p>
 */
public interface AgentTool {
    /**
     * 返回模型可见的工具名。
     *
     * @return 稳定且唯一的工具名
     */
    String toolName();

    /**
     * 执行模型提交的原始 JSON 参数。
     *
     * @param rawArguments LangChain4j 工具请求中的 JSON 对象文本
     * @param context 当前聊天 owner、请求 ID 和客户端能力上下文
     * @return 工具结果 JSON 文本
     * @throws Exception 工具参数或执行过程无效时抛出
     */
    String executeRaw(String rawArguments, AgentToolContext context) throws Exception;

    /**
     * 解析当前工具请求对应的风险定义。
     *
     * @param arguments 已解析的工具参数
     * @param context 当前聊天上下文
     * @return operation 定义；工具不支持风险确认或请求未命中定义时返回 null
     */
    default OperationDefinition definitionFor(Map<String, Object> arguments, AgentToolContext context) {
        return null;
    }

    /**
     * 从工具结果中提取需要发送给客户端的事件。
     *
     * <p>后端本地工具默认没有客户端事件；浏览器工具可以返回动作对象，runtime 会将
     * 它编码成统一的 SSE client-action 事件。返回值只允许是已经过工具自身校验的
     * JSON 对象。</p>
     *
     * @param parsedResult 已从完整工具输出解析出的对象
     * @return 客户端事件数据；没有事件时返回 null
     */
    default Map<String, Object> clientEvent(Map<String, Object> parsedResult) {
        return null;
    }
}
