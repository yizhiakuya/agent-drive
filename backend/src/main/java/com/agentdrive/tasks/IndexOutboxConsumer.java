package com.agentdrive.tasks;

import com.agentdrive.outbox.OutboxStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * 消费文件变更 outbox 事件并把它们转换为索引任务。
 * 通过事件 id 作为任务幂等键，确保重复轮询不会重复创建同一份索引工作。
 */
@Service
@Profile({"java-files", "java-auth", "java-chat"})
public class IndexOutboxConsumer {
    private final OutboxStore outbox;
    private final TaskStore tasks;

    /**
     * 创建 outbox 到任务库的投递器。
     * @param outbox 提供待发布文件变更事件的存储。
     * @param tasks 接收索引任务的任务存储。
     */
    public IndexOutboxConsumer(OutboxStore outbox, TaskStore tasks) {
        this.outbox = outbox;
        this.tasks = tasks;
    }

    /**
     * 批量读取待发布事件，将 {@code file.changed} 的 upsert、move/copy、delete 动作分别映射为
     * {@code index.file}、{@code index.rebuild}、{@code index.cleanup} 任务，并仅在任务入队后
     * 标记 outbox 事件已发布。事件 ID 进入任务去重键，格式错误或未知事件会跳过且不会伪造发布成功。
     * @param limit 本轮最多读取的 outbox 事件数，实际值限制在 1 到 100 之间。
     * @return 成功标记为已发布的事件数量。
     */
    public int consumeOnce(int limit) {
        int consumed = 0;
        for (Map<String, Object> event : outbox.pendingAll(Math.max(1, Math.min(limit, 100)))) {
            if (!"file.changed".equals(String.valueOf(event.get("event_type")))) continue;
            UUID userId;
            long eventId;
            try {
                userId = UUID.fromString(String.valueOf(event.get("user_id")));
                eventId = number(event.get("id"));
            } catch (RuntimeException invalidEvent) {
                continue;
            }
            Map<String, Object> payload = event.get("payload") instanceof Map<?, ?> map
                    ? cast(map) : Map.of();
            String action = String.valueOf(payload.getOrDefault("action", "upsert"));
            Object rawPaths = payload.get("paths");
            String path = rawPaths instanceof java.util.List<?> paths && !paths.isEmpty()
                    ? String.valueOf("move".equals(action) ? paths.get(paths.size() - 1) : paths.get(0)) : "";
            String type = switch (action) {
                case "delete" -> "index.cleanup";
                case "move", "copy" -> "index.rebuild";
                default -> "index.file";
            };
            Map<String, Object> taskPayload = "index.file".equals(type) ? Map.of("path", path) : Map.of();
            tasks.enqueue(userId, type, "index", taskPayload,
                    "outbox-index:" + eventId, "outbox.file.changed", null);
            if (outbox.markPublished(userId, eventId)) consumed++;
        }
        return consumed;
    }

    /**
     * 将事件 payload 的通配 Map 转为任务处理所需的字符串键 Map。
     * @param map 由反序列化得到的事件 payload。
     * @return 视图转换后的 payload；不复制底层数据。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /**
     * 把 JDBC 返回的数字对象或数字字符串统一转为事件 id。
     * @param value 事件 id 原始值。
     * @return 解析后的长整型事件 id。
     */
    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }
}
