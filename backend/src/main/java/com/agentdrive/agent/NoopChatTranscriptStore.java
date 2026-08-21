package com.agentdrive.agent;

import java.util.List;
import java.util.Map;

/**
 * 丢弃所有会话轨迹的测试替身。
 *
 * <p>所有方法都无副作用且不校验参数，用于关闭 transcript 持久化或隔离不需要检查
 * 会话写入的单元测试；不能用于需要恢复会话历史的生产运行时。</p>
 */
public final class NoopChatTranscriptStore implements ChatTranscriptStore {
    /** 丢弃用户消息，不写入任何存储。 */
    @Override
    public void appendUser(String sessionId, String content) {
    }

    /** 把每次上下文都视为新快照，但不写入任何存储。 */
    @Override
    public boolean appendContextIfChanged(String sessionId, String source, String kind, String content) {
        return true;
    }

    /** 丢弃助手正文和 reasoning，不写入任何存储。 */
    @Override
    public void appendAssistant(String sessionId, String content, String reasoning) {
    }

    /** 丢弃工具调用轨迹及其参数、输出和解析结果。 */
    @Override
    public void appendToolTrace(String sessionId, String tool, Map<String, Object> arguments,
                                String output, Map<String, Object> parsed) {
    }

    /** 丢弃会话最后工具轨迹快照。 */
    @Override
    public void updateLastTrace(String sessionId, List<Map<String, Object>> traces) {
    }
}
