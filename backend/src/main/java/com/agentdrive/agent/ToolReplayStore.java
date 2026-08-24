package com.agentdrive.agent;

import java.util.Map;

/**
 * 保存明确标记为可重放的工具执行结果。
 *
 * <p>调用方必须先根据 operation 的 {@link ReplayPolicy} 判断是否允许重放；普通 GET
 * 不自动缓存，因为文件和配置状态会变化。实现应在落库前脱敏参数和结果，并按
 * owner/session 隔离。</p>
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
     * 失效当前 session 的所有可重放结果。
     *
     * <p>文件、配置或索引 mutation 之后，之前缓存的读取/探测结果不再可靠。轻量
     * 测试实现可以保持 no-op，但生产实现必须删除或推进对应的 replay generation。</p>
     *
     * @param sessionId 当前会话
     */
    default void invalidate(String sessionId) {
        // 兼容只验证执行次数的测试替身。
    }

    /**
     * 一次工具调用可复用的结果快照。
     * @param output 原始工具输出
     * @param parsed 解析后的结构化输出
     */
    record ToolReplay(String output, Map<String, Object> parsed) {
    }
}
