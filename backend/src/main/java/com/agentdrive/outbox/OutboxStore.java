package com.agentdrive.outbox;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 为文件变更等事务外事件提供持久化 outbox 队列。
 * 事件先写入数据库，再由 Worker 投递为索引任务，从而避免文件已变更但任务未入队。
 */
public interface OutboxStore {
    /**
     * 写入一条待投递事件；相同幂等键不会重复创建事件。
     * @param userId 事件所属用户的 UUID。
     * @param eventType 事件类型，例如 {@code file.changed}。
     * @param aggregateType 事件关联聚合的类型。
     * @param aggregateId 事件关联聚合的标识。
     * @param idempotencyKey 由变更生成的稳定去重键。
     * @param payload 投递给消费者的事件内容。
     * @return 新事件写入成功时返回 {@code true}，幂等键已存在时返回 {@code false}。
     */
    boolean enqueue(UUID userId, String eventType, String aggregateType, String aggregateId,
                    String idempotencyKey, Map<String, Object> payload);

    /**
     * 读取指定用户尚未发布的 outbox 事件。
     * @param userId 事件所属用户的 UUID。
     * @param limit 本次最多读取的事件数。
     * @return 按事件 id 排序的待发布事件。
     */
    List<Map<String, Object>> pending(UUID userId, int limit);

    /**
     * 读取所有用户尚未发布的 outbox 事件，供后台消费者轮询。
     * @param limit 本次最多读取的事件数。
     * @return 按事件 id 排序的待发布事件。
     */
    List<Map<String, Object>> pendingAll(int limit);

    /**
     * 将指定事件标记为已发布，防止下一轮轮询重复生成任务。
     * @param userId 事件所属用户的 UUID。
     * @param eventId outbox 事件 id。
     * @return 事件存在且本次完成标记时返回 {@code true}。
     */
    boolean markPublished(UUID userId, long eventId);

    /**
     * 记录一次投递失败；不可恢复事件同时进入死信状态，之后不再参与 pending 查询。
     * 该入口只供跨 owner Worker 按数据库事件 ID 使用，不接受事件 payload 提供的任意标识。
     *
     * @param eventId outbox 数据库主键。
     * @param error 稳定、无敏感信息的失败原因。
     * @param deadLetter 是否将事件永久隔离。
     * @return 本次是否更新了仍待处理的事件。
     */
    boolean recordFailure(long eventId, String error, boolean deadLetter);
}
