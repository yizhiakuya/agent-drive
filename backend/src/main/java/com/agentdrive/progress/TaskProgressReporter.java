package com.agentdrive.progress;

/**
 * 把耗时任务的阶段进度报告给任务状态存储。
 * 实现可以选择节流或合并更新；业务服务只描述当前阶段，不直接依赖 PostgreSQL。
 */
@FunctionalInterface
public interface TaskProgressReporter {
    TaskProgressReporter NOOP = (current, total, message) -> { };

    /**
     * 报告当前阶段的进度。
     *
     * @param current 当前已处理数量；未知数量时可以使用已处理数并把 total 设为 0。
     * @param total 当前阶段总数量；未知时为 0。
     * @param message 面向任务详情的阶段和当前对象说明。
     */
    void report(int current, int total, String message);

    /**
     * 立即报告阶段边界或终态；默认实现与普通报告相同，节流实现可以覆盖它。
     *
     * @param current 当前阶段已处理数量。
     * @param total 当前阶段总数量；未知时为 0。
     * @param message 当前阶段说明。
     */
    default void reportNow(int current, int total, String message) {
        report(current, total, message);
    }

    /**
     * 仅续租当前任务，不产生新的进度文案；长时间外部调用可周期调用它。
     * 普通同步业务使用默认空实现保持兼容。
     */
    default void heartbeat() {
    }

    /**
     * 获取不产生持久化副作用的回调，供同步调用方保持兼容。
     *
     * @return 空进度回调。
     */
    static TaskProgressReporter noop() {
        return NOOP;
    }
}
