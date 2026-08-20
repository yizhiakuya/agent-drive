package com.agentdrive.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * owner 级 outbox 事件写入、待发布查询和发布标记的 MyBatis 映射接口。
 *
 * <p>事件正文以 PostgreSQL {@code jsonb} 保存；待发布查询只返回 {@code published_at} 为空的
 * 事件，发布标记通过 owner 和事件 ID 做并发幂等保护。
 */
@Mapper
public interface OutboxMapper {
    /**
     * 写入一条 owner 的 outbox 事件。
     *
     * <p>事件正文转换为 {@code jsonb} 保存；若 {@code idempotencyKey} 已触发数据库唯一约束，
     * {@code ON CONFLICT DO NOTHING} 会跳过重复事件，不覆盖已有事件。
     *
     * @param userId 事件所属 owner 的 UUID 字符串
     * @param eventType 事件类型
     * @param aggregateType 事件关联聚合的类型
     * @param aggregateId 事件关联聚合的标识
     * @param idempotencyKey 防止同一事件重复写入的幂等键
     * @param payload 事件正文的 JSON 文本
     * @return 新插入的事件数；因幂等键冲突跳过时为 {@code 0}
     */
    int insert(@Param("userId") String userId,
               @Param("eventType") String eventType,
               @Param("aggregateType") String aggregateType,
               @Param("aggregateId") String aggregateId,
               @Param("idempotencyKey") String idempotencyKey,
               @Param("payload") String payload);

    /**
     * 查询指定 owner 尚未发布的 outbox 事件。
     *
     * <p>SQL 过滤该 owner 且尚未发布、未进入死信的事件，按事件 ID 升序返回，时间字段
     * 以 Unix epoch 秒数提供，并受 {@code limit} 限制。
     *
     * @param userId 事件所属 owner 的 UUID 字符串
     * @param limit 本次最多返回的事件数
     * @return 待发布事件字段映射列表；没有待发布事件时返回空列表
     */
    List<Map<String, Object>> pending(@Param("userId") String userId, @Param("limit") int limit);

    /**
     * 查询所有 owner 尚未发布的 outbox 事件。
     *
     * <p>SQL 不带 owner 条件，仅过滤尚未发布、未进入死信的事件，按事件 ID 升序返回并受
     * {@code limit} 限制；返回映射包含事件所属的 {@code user_id}。
     *
     * @param limit 本次最多返回的事件数
     * @return 跨 owner 的待发布事件字段映射列表；没有待发布事件时返回空列表
     */
    List<Map<String, Object>> pendingAll(@Param("limit") int limit);

    /**
     * 将 owner 指定的未发布 outbox 事件标记为已发布。
     *
     * <p>SQL 只更新 owner、事件 ID 匹配且尚未发布、未进入死信的记录，将发布时间设为
     * 当前时间；已发布事件不会被重复改写。
     *
     * @param userId 事件所属 owner 的 UUID 字符串
     * @param eventId outbox 事件的数据库 ID
     * @return 实际标记的事件数；事件不存在、owner 不匹配或已发布时为 {@code 0}
     */
    int markPublished(@Param("userId") String userId, @Param("eventId") long eventId);

    /** 累加投递失败并按需把不可恢复事件移出待发布队列。 */
    int recordFailure(@Param("eventId") long eventId, @Param("error") String error,
                      @Param("deadLetter") boolean deadLetter);
}
