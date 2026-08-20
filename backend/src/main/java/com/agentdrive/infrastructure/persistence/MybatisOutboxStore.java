package com.agentdrive.infrastructure.persistence;

import com.agentdrive.infrastructure.persistence.mapper.OutboxMapper;
import com.agentdrive.outbox.OutboxStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 通过 MyBatis 实现 owner-scoped outbox 事件存储。
 * <p>业务事务先写事件，发布器随后读取未发布事件并以 event ID 标记完成；payload 在边界处编码为 JSON，
 * 读取时解析失败会携带显式 {@code payload_error}，由消费者持久化为死信而不是伪造空事件。</p>
 */
public class MybatisOutboxStore implements OutboxStore {
    private final OutboxMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * 保存 outbox Mapper 和 JSON 映射器。
     * @param mapper 读写 outbox 事件及发布时间的 Mapper。
     * @param objectMapper 编解码事件 payload 的 Jackson 映射器。
     */
    public MybatisOutboxStore(OutboxMapper mapper, ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 在当前事务中插入一个待发布事件。
     * @param userId 事件所属 owner 的 UUID。
     * @param eventType 事件名称。
     * @param aggregateType 事件关联聚合类型。
     * @param aggregateId 事件关联聚合 ID。
     * @param idempotencyKey 用于防止同一业务事件重复写入的键。
     * @param payload 事件数据，会序列化为 JSON。
     * @return 插入一行时为 {@code true}。
     */
    @Override
    @Transactional
    public boolean enqueue(UUID userId, String eventType, String aggregateType, String aggregateId,
                           String idempotencyKey, Map<String, Object> payload) {
        requireUser(userId);
        return mapper.insert(userId.toString(), eventType, aggregateType, aggregateId, idempotencyKey,
                json(payload == null ? Map.of() : payload)) > 0;
    }

    /**
     * 读取一个 owner 尚未发布的事件批次。
     * @param userId 事件所属 owner 的 UUID。
     * @param limit 最大返回数量，限制在 1 到 500。
     * @return 规范化事件列表。
     */
    @Override
    public List<Map<String, Object>> pending(UUID userId, int limit) {
        requireUser(userId);
        return mapper.pending(userId.toString(), Math.max(1, Math.min(limit, 500))).stream()
                .map(this::normalize).toList();
    }

    /**
     * 跨 owner 读取待发布事件，供独立发布 Worker 使用。
     * @param limit 最大返回数量，限制在 1 到 500。
     * @return 规范化事件列表。
     */
    @Override
    public List<Map<String, Object>> pendingAll(int limit) {
        return mapper.pendingAll(Math.max(1, Math.min(limit, 500))).stream()
                .map(this::normalize).toList();
    }

    /**
     * 将 owner 的指定事件标记为已发布。
     * @param userId 事件所属 owner 的 UUID。
     * @param eventId outbox 事件 ID。
     * @return 实际更新一行时为 {@code true}。
     */
    @Override
    @Transactional
    public boolean markPublished(UUID userId, long eventId) {
        requireUser(userId);
        return mapper.markPublished(userId.toString(), eventId) > 0;
    }

    /** 记录 Worker 投递失败，并可将不可恢复事件持久化为死信。 */
    @Override
    @Transactional
    public boolean recordFailure(long eventId, String error, boolean deadLetter) {
        if (eventId <= 0) throw new IllegalArgumentException("eventId must be positive");
        String message = error == null || error.isBlank() ? "outbox_delivery_failed" : error;
        return mapper.recordFailure(eventId, message.substring(0, Math.min(1000, message.length())),
                deadLetter) > 0;
    }

    /**
     * 将 outbox 数据库行转换为发布器使用的字段 Map。
     * @param row Mapper 返回的事件行。
     * @return 包含聚合信息、payload 和发布时间的有序 Map。
     */
    private Map<String, Object> normalize(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("user_id", row.get("user_id"));
        result.put("event_type", row.get("event_type"));
        result.put("aggregate_type", row.get("aggregate_type"));
        result.put("aggregate_id", row.get("aggregate_id"));
        result.put("idempotency_key", row.get("idempotency_key"));
        ParsedPayload parsed = parse(row.get("payload_json"));
        result.put("payload", parsed.value());
        if (parsed.error() != null) result.put("payload_error", parsed.error());
        result.put("failure_count", row.get("failure_count"));
        result.put("last_error", row.get("last_error"));
        result.put("created_at", row.get("created_at"));
        result.put("published_at", row.get("published_at"));
        result.put("dead_lettered_at", row.get("dead_lettered_at"));
        return result;
    }

    /**
     * 解析 outbox payload JSON。
     * @param value JSON 文本或数据库值。
     * @return 解析值和可选稳定错误；坏 JSON 不会被当作有效空 payload。
     */
    private ParsedPayload parse(Object value) {
        if (value == null) return new ParsedPayload(Map.of(), "missing_payload");
        try {
            return new ParsedPayload(objectMapper.readValue(String.valueOf(value), Object.class), null);
        } catch (JsonProcessingException error) {
            return new ParsedPayload(Map.of(), "invalid_payload_json");
        }
    }

    /**
     * 将事件 payload 编码为 JSON。
     * @param value 事件数据对象。
     * @return JSON 文本。
     * @throws IllegalArgumentException payload 无法序列化时抛出。
     */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("outbox payload must be JSON", error);
        }
    }

    /**
     * 校验 owner 作用域，防止无 owner 的事件访问进入 Mapper。
     * @param userId 事件所属 owner 的 UUID。
     * @throws IllegalArgumentException userId 为空时抛出。
     */
    private static void requireUser(UUID userId) {
        if (userId == null) throw new IllegalArgumentException("userId must not be null");
    }

    private record ParsedPayload(Object value, String error) {
    }
}
