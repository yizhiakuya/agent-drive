package com.agentdrive.tasks;

import java.util.Map;

/**
 * 提供任务 Worker 的租约和状态迁移持久化操作。
 * 领取、续租、成功、失败都必须校验 worker id，避免租约过期后旧 Worker 修改新租约。
 */
public interface TaskWorkerStore {
    /**
     * 返回最近十秒内刷新过心跳的 Worker 数量，供公开 readiness 探针使用。
     * 非持久化替身默认返回零，避免把未知状态误报为就绪。
     */
    default int onlineWorkerCount() {
        return 0;
    }

    /**
     * 登记 Worker 进程仍在运行，并刷新其持久化心跳时间。
     * Worker 即使当前没有任务也必须调用该方法，否则 API 无法区分“空闲”与“已掉线”。
     *
     * @param workerId 当前 Worker 的唯一标识。
     */
    void touchWorker(String workerId);

    /**
     * 删除正常关闭的 Worker 登记。
     * 非正常退出不依赖该方法，在线统计会在心跳窗口结束后自动排除过期记录。
     *
     * @param workerId 要注销的 Worker 唯一标识。
     */
    void removeWorker(String workerId);

    /**
     * 按 lane 原子领取一条可执行任务，并将其置为 running、写入 worker id 和租约到期时间。
     * @param workerId 当前 Worker 的唯一标识。
     * @param lane 要消费的任务 lane。
     * @param leaseSeconds 本次租约持续秒数。
     * @return 领取到的任务记录；没有可领取任务时返回 {@code null}。
     */
    Map<String, Object> claim(String workerId, String lane, int leaseSeconds);

    /**
     * 延长当前 Worker 持有任务的租约，防止长时间抽取或向量请求被误判为超时。
     * @param workerId 当前 Worker 的唯一标识。
     * @param taskId 要续租的任务 UUID 字符串。
     * @param leaseSeconds 续租后新的租约持续秒数。
     * @return 任务仍由该 Worker 持有并成功续租时为 {@code true}。
     */
    boolean heartbeat(String workerId, String taskId, int leaseSeconds);

    /**
     * 更新任务的阶段进度，并在同一条原子更新中续租当前任务。
     * 只有仍由该 Worker 持有有效租约的运行中任务才允许写入；实现会为有效更新追加 progress 事件。
     *
     * @param workerId 当前租约持有者的 Worker 标识。
     * @param taskId 要更新的任务 UUID 字符串。
     * @param current 当前阶段已处理数量。
     * @param total 当前阶段总数量；未知时为 0。
     * @param message 当前阶段和对象说明。
     * @param leaseSeconds 更新后新的租约持续秒数。
     * @return 任务仍由该 Worker 持有并成功更新时为 {@code true}。
     */
    boolean updateProgress(String workerId, String taskId, int current, int total,
                           String message, int leaseSeconds);

    /**
     * 在租约仍有效且归属匹配时把任务落为 terminal 成功状态并保存结果。
     * @param workerId 完成任务的 Worker 唯一标识。
     * @param taskId 已执行任务的 UUID 字符串。
     * @param result 任务成功结果。
     * @return 成功完成状态迁移时为 {@code true}，租约失效或任务不存在时为 {@code false}。
     */
    boolean succeed(String workerId, String taskId, Map<String, Object> result);

    /**
     * 记录本次执行错误，并按 max attempts 将任务置为 retry_wait 或最终失败。
     * @param workerId 执行任务的 Worker 唯一标识。
     * @param taskId 失败任务的 UUID 字符串。
     * @param error 可写入任务事件的错误摘要。
     * @return 成功记录失败状态时为 {@code true}。
     */
    boolean fail(String workerId, String taskId, String error);

    /**
     * 回收已过期租约，将可重试任务置为 retry_wait，并把取消或超过尝试次数的任务置为终态。
     * @return 本次回收或终止的任务数量。
     */
    int recoverExpiredLeases();
}
