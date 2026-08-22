package com.agentdrive.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * Worker 领取和推进任务状态的 PostgreSQL 持久化 Mapper。
 * SQL 以租约持有人和当前状态保护并发更新；任务选择使用行锁跳过已被其他 Worker 锁定的候选任务。
 * 本接口只负责 MyBatis 状态更新和事件写入，不负责任务处理器本身的业务执行。
 */
@Mapper
public interface TaskWorkerMapper {
    /** Count workers whose heartbeat is within the public readiness window. */
    int selectOnlineWorkerCount();

    /**
     * 插入或刷新 Worker 进程心跳。
     * SQL 使用 Worker ID 做幂等键，允许 Worker 空闲时仍保持在线状态。
     * @param workerId 当前 Worker 的唯一标识。
     * @return 实际写入或更新的行数。
     */
    int touchWorker(@Param("workerId") String workerId);

    /**
     * 删除正常停止的 Worker 心跳记录。
     * @param workerId 要注销的 Worker 标识。
     * @return 实际删除的行数。
     */
    int removeWorker(@Param("workerId") String workerId);

    /**
     * 原子领取一个当前可执行的任务。
     * SQL 只选择指定 lane 中状态为 {@code queued} 或 {@code retry_wait}、已到 {@code available_at} 且租约为空或已过期的任务，
     * 按优先级降序、创建时间升序选一行，并用 {@code FOR UPDATE SKIP LOCKED} 避免 Worker 争抢。
     * 随后同一 SQL 将其置为 {@code running}，递增尝试次数，写入 Worker、租约截止时间、心跳和首次开始时间。
     * @param workerId 领取任务的 Worker 标识，将写入 {@code lease_owner}。
     * @param lane 需要领取的执行 lane。
     * @param leaseSeconds 新租约持续秒数。
     * @return 被领取任务的 ID、owner、类型、payload、尝试次数和租约等字段；没有可领取任务时返回 {@code null}。
     */
    Map<String, Object> claim(@Param("workerId") String workerId,
                              @Param("lane") String lane,
                              @Param("leaseSeconds") int leaseSeconds);

    /**
     * 为仍由当前 Worker 持有且尚未过期的任务续租。
     * SQL 只允许 {@code running} 或 {@code cancelling} 状态、匹配 {@code lease_owner} 且原租约仍有效的任务，
     * 更新租约截止时间、心跳时间和修改时间；租约丢失时不会重新夺回任务。
     * @param workerId 当前租约持有者的 Worker 标识。
     * @param taskId 要续租的任务 UUID 字符串。
     * @param leaseSeconds 新租约持续秒数。
     * @return SQL 实际更新的行数；不再持有有效租约时为 {@code 0}。
     */
    int heartbeat(@Param("workerId") String workerId,
                  @Param("taskId") String taskId,
                  @Param("leaseSeconds") int leaseSeconds);

    /**
     * 原子更新任务阶段进度并续租。
     * SQL 仍要求 Worker 持有未过期租约，避免旧 Worker 在任务被回收后覆盖新执行者的进度。
     * @param workerId 当前租约持有者。
     * @param taskId 任务 UUID 字符串。
     * @param current 当前阶段计数。
     * @param total 当前阶段总数，未知时为 0。
     * @param message 当前阶段说明。
     * @param leaseSeconds 续租秒数。
     * @return 实际更新行数。
     */
    int updateProgress(@Param("workerId") String workerId,
                       @Param("taskId") String taskId,
                       @Param("current") int current,
                       @Param("total") int total,
                       @Param("message") String message,
                       @Param("leaseSeconds") int leaseSeconds);

    /**
     * 将当前 Worker 持有的任务标记为成功。
     * SQL 只更新匹配 Worker 且处于 {@code running} 或 {@code cancelling} 的任务，将结果写为 {@code jsonb}，
     * 清除错误和租约字段，并写入完成时间；因此取消请求在处理成功提交前不会阻止该成功分支。
     * @param workerId 当前租约持有者的 Worker 标识。
     * @param taskId 要完成的任务 UUID 字符串。
     * @param result 任务结果 JSON 文本，将转换为 {@code jsonb}。
     * @return SQL 实际更新的行数；租约或状态不匹配时为 {@code 0}。
     */
    int succeed(@Param("workerId") String workerId,
                @Param("taskId") String taskId,
                @Param("result") String result);

    /**
     * 记录当前 Worker 的执行失败并按尝试次数推进任务状态。
     * 有取消请求时进入 {@code cancelled}、清空错误并立即结束；否则尚未达到 {@code max_attempts} 时进入
     * {@code retry_wait} 并延迟 30 秒，达到上限时进入 {@code failed}。所有分支都会清除租约，只有终态写入完成时间。
     * SQL 仍要求 Worker 持有任务且原状态为 {@code running} 或 {@code cancelling}。
     * @param workerId 当前租约持有者的 Worker 标识。
     * @param taskId 失败任务的 UUID 字符串。
     * @param error 可重试或最终失败时保存的错误文本；取消分支不会保存它。
     * @return SQL 实际更新的行数；租约或状态不匹配时为 {@code 0}。
     */
    int fail(@Param("workerId") String workerId,
             @Param("taskId") String taskId,
             @Param("error") String error);

    /**
     * 回收所有已过期的运行中任务租约。
     * SQL 处理 {@code running} 和 {@code cancelling} 且 {@code lease_until < now()} 的任务：有取消请求时标为
     * {@code cancelled}，达到最大尝试次数时标为 {@code failed}，其余回到 {@code retry_wait}；随后清空租约并立即可再次领取。
     * 终态写入完成时间，取消分支清除错误；该操作不依赖具体 Worker。
     * @return SQL 实际更新的任务数量。
     */
    int recoverExpiredLeases();

    /**
     * 为任务追加一条 Worker 事件。
     * SQL 将任务 UUID、事件类型和 JSON payload 插入 {@code task_events}；事件 ID 与创建时间由数据库生成，
     * 本 Mapper 不在插入语句中校验用户归属。
     * @param taskId 事件所属任务的 UUID 字符串。
     * @param type 事件类型，例如 {@code claimed}。
     * @param payload 事件 JSON 文本，将转换为 {@code jsonb}。
     * @return SQL 实际插入的行数。
     */
    int insertEvent(@Param("taskId") String taskId,
                    @Param("type") String type,
                    @Param("payload") String payload);
}
