package com.agentdrive.agent;

import java.util.List;
import java.util.Map;

/**
 * 保存 Agent 会话的用户消息、上下文注入、助手回复、思考内容和工具轨迹。
 *
 * <p>实现应按会话 ID 归档记录，并遵守项目的敏感信息脱敏约束；接口本身不规定
 * 使用数据库、文件还是测试替身。</p>
 */
public interface ChatTranscriptStore {
    /**
     * 返回当前会话已经成功读取过的 Skill 名称。
     *
     * <p>Skill 正文不直接信任客户端历史；生产实现从 owner 会话内已持久化的
     * {@code read_skill action=read} 工具轨迹中提取名称，下一轮再由当前 Skill
     * registry 解析最新正文。测试替身没有历史时返回空列表。</p>
     *
     * @param sessionId 会话 UUID 文本
     * @return 去重后的已读取 Skill 名称
     */
    default List<String> loadedSkillNames(String sessionId) {
        return List.of();
    }

    /**
     * 追加一条用户消息。
     * @param sessionId 会话 UUID 的字符串表示
     * @param content 用户实际发送的正文
     */
    void appendUser(String sessionId, String content);

    /**
     * 当同来源的最新上下文与当前快照不同时追加一条上下文消息。
     * @param sessionId 会话 UUID 的字符串表示
     * @param source 上下文来源名称
     * @param kind 上下文类型
     * @param content 模型读取的完整上下文文本
     * @return 实际追加新消息时为 true；内容未变化或会话 ID 非法时为 false
     */
    boolean appendContextIfChanged(String sessionId, String source, String kind, String content);

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

    /**
     * 保存最近一次上下文窗口用量，供重新打开会话时恢复展示。
     *
     * <p>轻量测试替身默认忽略该元数据；生产实现应按会话 ID 原子覆盖，不能把
     * token 数量写入消息正文或工具轨迹。</p>
     *
     * @param sessionId 会话 UUID 的字符串表示
     * @param usage 已用、上限、百分比及可选输入/输出 token
     */
    default void updateContextUsage(String sessionId, Map<String, Object> usage) {
        // 兼容只验证消息/工具轨迹的轻量测试存储。
    }
}
