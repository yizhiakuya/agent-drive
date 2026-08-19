package com.agentdrive.agent;

import java.util.List;
import java.util.Map;

/**
 * 保存 Agent 会话的用户消息、助手回复、思考内容和工具轨迹。
 *
 * <p>实现应按会话 ID 归档记录，并遵守项目的敏感信息脱敏约束；接口本身不规定
 * 使用数据库、文件还是测试替身。</p>
 */
public interface ChatTranscriptStore {
    /**
     * 追加一条用户消息。
     * @param sessionId 会话 UUID 的字符串表示
     * @param content 用户实际发送的正文
     */
    void appendUser(String sessionId, String content);

    /**
     * 追加助手的最终正文及独立思考内容。
     * @param sessionId 会话 UUID 的字符串表示
     * @param content 助手可展示的正文，可为空
     * @param reasoning Provider 返回的思考文本，可为空且不应进入下一轮 history
     */
    void appendAssistant(String sessionId, String content, String reasoning);

    /**
     * 保存一次工具调用的参数、原始输出和解析结果。
     * @param sessionId 会话 UUID 的字符串表示
     * @param tool 实际调用的工具名称
     * @param arguments 工具调用参数，持久化前必须脱敏
     * @param output 工具原始输出，可能包含较长文本
     * @param parsed 从完整输出解析出的结构化结果
     */
    void appendToolTrace(String sessionId,
                         String tool,
                         Map<String, Object> arguments,
                         String output,
                         Map<String, Object> parsed);

    /**
     * 替换会话的最后工具轨迹快照，供短消息续接任务路由和重放使用。
     * @param sessionId 会话 UUID 的字符串表示
     * @param traces 本轮工具步骤的有序结构化记录
     */
    void updateLastTrace(String sessionId, List<Map<String, Object>> traces);
}
