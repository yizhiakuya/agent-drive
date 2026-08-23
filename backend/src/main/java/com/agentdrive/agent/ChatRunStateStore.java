package com.agentdrive.agent;

import java.util.Map;

/** 持久化 Agent run 的生命周期摘要，不保存可直接重放的用户凭据或工具原文。 */
public interface ChatRunStateStore {
    /** 记录一个新的 running run。 */
    void start(String sessionId);

    /** 更新运行阶段和终态。 */
    void update(String sessionId, String status, String phase);

    /** 读取最近一次 run 状态；非法或未知会话返回空 Map。 */
    Map<String, Object> find(String sessionId);

    /** 进程启动时把上一进程遗留的 running 标记收敛为 interrupted。 */
    void markInterrupted();

    /** 兼容不需要持久状态的测试替身。 */
    static ChatRunStateStore noop() {
        return new ChatRunStateStore() {
            @Override public void start(String sessionId) { }
            @Override public void update(String sessionId, String status, String phase) { }
            @Override public Map<String, Object> find(String sessionId) { return Map.of(); }
            @Override public void markInterrupted() { }
        };
    }
}
