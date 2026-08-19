package com.agentdrive.agent;

import java.util.Map;

/**
 * 保存可安全重放的非 red 工具执行结果。
 *
 * <p>调用方应使用 sessionId、工具名和完整参数确定幂等键；命中后可复用原始输出和
 * parsed 结果，避免再次触发外部副作用。生产实现需要持久化并按 owner/session 隔离。</p>
 */
public interface ToolReplayStore {
    /**
     * 查找相同会话、工具和参数的历史结果。
     * @param sessionId 会话标识
     * @param tool 工具名
     * @param arguments 完整工具参数
     * @return 可重放结果；未命中时返回 null
     */
    ToolReplay find(String sessionId, String tool, Map<String, Object> arguments);

    /**
     * 保存一次成功工具执行的结果供后续精确重放。
     * @param sessionId 会话标识
     * @param tool 工具名
     * @param arguments 产生该结果的完整参数
     * @param output 工具原始输出
     * @param parsed 从完整输出解析的结果
     */
    void save(String sessionId, String tool, Map<String, Object> arguments, String output,
              Map<String, Object> parsed);

    /**
     * 一次工具调用可复用的结果快照。
     * @param output 原始工具输出
     * @param parsed 解析后的结构化输出
     */
    record ToolReplay(String output, Map<String, Object> parsed) {
    }
}
