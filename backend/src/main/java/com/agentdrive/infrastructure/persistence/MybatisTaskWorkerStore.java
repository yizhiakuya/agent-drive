package com.agentdrive.infrastructure.persistence;

import com.agentdrive.infrastructure.persistence.mapper.TaskWorkerMapper;
import com.agentdrive.tasks.TaskWorkerStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;

/**
 * 通过 MyBatis 实现 Worker 的租约和任务状态转换。
 * <p>租约时间被限制在 5 秒到 3600 秒；只有数据库实际更新任务时才写 succeeded/failed 事件，
 * 避免过期 Worker 对已被其他 Worker 接管的任务追加错误状态。</p>
 */
public class MybatisTaskWorkerStore implements TaskWorkerStore {
    private final TaskWorkerMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * 保存 Worker SQL Mapper 和结果 JSON 映射器。
     * @param mapper 执行 claim、heartbeat、完成/失败和租约恢复的 Mapper。
     * @param objectMapper 编码任务结果的 Jackson 映射器。
     */
    public MybatisTaskWorkerStore(TaskWorkerMapper mapper, ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 把 Worker 的进程心跳写入独立登记表。
     * @param workerId 当前 Worker 的稳定进程标识。
     */
    @Override
    public void touchWorker(String workerId) {
        mapper.touchWorker(requireWorkerId(workerId));
    }

    /**
     * 清理正常关闭的 Worker 登记，避免优雅重启留下无意义的活动记录。
     * @param workerId 要注销的 Worker 标识。
     */
    @Override
    public void removeWorker(String workerId) {
        mapper.removeWorker(requireWorkerId(workerId));
    }

    /**
     * 为 Worker 原子领取一个 lane 中可执行的任务并写 claimed 事件。
     * @param workerId 当前 Worker 的稳定 ID。
     * @param lane 要领取的任务 lane。
     * @param leaseSeconds 租约秒数，限制在 5 到 3600。
     * @return 被领取任务的数据库行；当前没有可领取任务时为 {@code null}。
     */
    @Override
    public Map<String, Object> claim(String workerId, String lane, int leaseSeconds) {
        Map<String, Object> task = mapper.claim(workerId, lane, Math.max(5, Math.min(3600, leaseSeconds)));
        if (task != null) mapper.insertEvent(String.valueOf(task.get("id")), "claimed", "{}");
        return task;
    }

    /**
     * 延长 Worker 持有的任务租约。
     * @param workerId 当前 Worker 的 ID。
     * @param taskId 已领取任务的 UUID 文本。
     * @param leaseSeconds 新租约秒数，限制在 5 到 3600。
     * @return 任务仍由该 Worker 持有并成功续租时为 {@code true}。
     */
    @Override
    public boolean heartbeat(String workerId, String taskId, int leaseSeconds) {
        return mapper.heartbeat(workerId, taskId, Math.max(5, Math.min(3600, leaseSeconds))) > 0;
    }

    /**
     * 在 Worker 仍持有租约时将任务置为 succeeded，并记录事件。
     * @param workerId 当前 Worker 的 ID。
     * @param taskId 已领取任务的 UUID 文本。
     * @param result 任务结果对象，会序列化为 JSON。
     * @return 数据库成功更新任务时为 {@code true}。
     * @throws IllegalArgumentException result 无法序列化时抛出。
     */
    @Override
    public boolean succeed(String workerId, String taskId, Map<String, Object> result) {
        int updated = mapper.succeed(workerId, taskId, json(result == null ? Map.of() : result));
        if (updated > 0) mapper.insertEvent(taskId, "succeeded", "{}");
        return updated > 0;
    }

    /**
     * 在 Worker 仍持有租约时将任务置为 failed，并记录事件。
     * @param workerId 当前 Worker 的 ID。
     * @param taskId 已领取任务的 UUID 文本。
     * @param error 失败原因；非空文本截断到 2000 个 Java 字符。
     * @return 数据库成功更新任务时为 {@code true}。
     */
    @Override
    public boolean fail(String workerId, String taskId, String error) {
        int updated = mapper.fail(workerId, taskId, error == null ? "task failed" : error.substring(0, Math.min(2000, error.length())));
        if (updated > 0) mapper.insertEvent(taskId, "failed", "{}");
        return updated > 0;
    }

    /**
     * 回收已过期任务租约，使任务重新进入可领取状态。
     * @return 被恢复的任务数量。
     */
    @Override
    public int recoverExpiredLeases() {
        return mapper.recoverExpiredLeases();
    }

    /**
     * 将 Worker 结果编码为数据库 JSON。
     * @param value 任务结果对象。
     * @return JSON 文本。
     * @throws IllegalArgumentException 结果无法序列化时抛出。
     */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("task result must be JSON", error);
        }
    }

    /**
     * 拒绝空 Worker 标识，避免把多进程心跳合并到同一条无名记录。
     * @param workerId 调用方提供的 Worker 标识。
     * @return 去除首尾空白后的非空标识。
     */
    private String requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        return workerId.trim();
    }
}
