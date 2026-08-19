package com.agentdrive.infrastructure.persistence;

import com.agentdrive.infrastructure.persistence.mapper.ScheduleMapper;
import com.agentdrive.tasks.ScheduleStore;
import com.agentdrive.tasks.TaskStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.UUID;

/**
 * 通过 MyBatis 持久化计划并把到期计划转换成任务队列项。
 * <p>支持 cron（按当前实现的固定 60 秒推进）、interval 和 daily 计划；派发使用
 * {@code schedule:id:scheduledAt} 去重键，并在同一事务中更新下一次运行时间。</p>
 */
public class MybatisScheduleStore implements ScheduleStore {
    private final ScheduleMapper mapper;
    private final ObjectMapper objectMapper;
    private final com.agentdrive.tasks.TaskStore tasks;

    /**
     * 保存计划 Mapper、JSON 映射器和任务入队端口。
     * @param mapper 读写计划及 next_run_at 的 Mapper。
     * @param objectMapper 编解码计划 payload 的 Jackson 映射器。
     * @param tasks 计划到期时创建或去重任务的任务存储。
     */
    public MybatisScheduleStore(ScheduleMapper mapper, ObjectMapper objectMapper, com.agentdrive.tasks.TaskStore tasks) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.tasks = Objects.requireNonNull(tasks, "tasks must not be null");
    }

    /**
     * 列出 owner 的全部计划，并把 payload JSON 转成对象。
     * @param userId 计划所属 owner 的 UUID。
     * @return 规范化的计划列表。
     */
    @Override
    public List<Map<String, Object>> list(UUID userId) {
        requireUser(userId);
        return mapper.selectSchedules(userId.toString()).stream().map(this::normalize).toList();
    }

    /**
     * 在事务内创建或更新 owner 的计划。
     * @param userId 数据所属用户的唯一标识。
     * @param name 计划在 owner 下的唯一名称，不能为空。
     * @param cron cron 表达式或兼容字段；为空时使用 scheduleValue。
     * @param scheduleKind 计划类型，空值默认为 {@code cron}。
     * @param scheduleValue interval 秒数或 daily 的 HH:mm 值。
     * @param taskType 到期后入队的任务类型，不能为空。
     * @param lane 到期任务使用的 Worker lane，空值为 {@code default}。
     * @param payload 到期任务 payload，会序列化为 JSON。
     * @param enabled 是否启用计划。
     * @param priority 任务优先级，最小按 0 保存。
     * @param maxAttempts 最大尝试次数，最小按 1 保存。
     * @param timezone daily 计划的时区，空值为 {@code UTC}。
     * @return 更新后的计划 Map。
     * @throws IllegalArgumentException owner、名称或 taskType 缺失，或 payload 无法编码时抛出。
     */
    @Override
    @Transactional
    public Map<String, Object> upsert(UUID userId, String name, String cron, String scheduleKind,
                                       String scheduleValue, String taskType, String lane,
                                       Map<String, Object> payload, boolean enabled, int priority,
                                       int maxAttempts, String timezone) {
        requireUser(userId);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("schedule name is required");
        if (taskType == null || taskType.isBlank()) throw new IllegalArgumentException("task_type is required");
        return normalize(mapper.upsert(
                userId.toString(), name, cron == null ? scheduleValue : cron,
                scheduleKind == null ? "cron" : scheduleKind,
                scheduleValue == null ? cron : scheduleValue,
                taskType, lane == null ? "default" : lane, json(payload == null ? Map.of() : payload),
                enabled, Math.max(0, priority), Math.max(1, maxAttempts),
                timezone == null || timezone.isBlank() ? "UTC" : timezone
        ));
    }

    /**
     * 删除 owner 下指定名称的计划。
     * @param userId 计划所属 owner 的 UUID。
     * @param name 计划名称。
     * @return 实际删除一行时为 {@code true}。
     */
    @Override
    @Transactional
    public boolean delete(UUID userId, String name) {
        requireUser(userId);
        return mapper.delete(userId.toString(), name) > 0;
    }

    /**
     * 查询 owner 的到期计划并逐个入队任务。
     * @param userId 计划所属 owner 的 UUID。
     * @param limit 最多处理计划数，限制在 1 到 20。
     * @return 每个计划对应的名称、是否新建任务和任务快照。
     */
    @Override
    @Transactional
    public List<Map<String, Object>> dispatchDue(UUID userId, int limit) {
        requireUser(userId);
        List<Map<String, Object>> created = new ArrayList<>();
        for (Map<String, Object> schedule : mapper.selectDue(userId.toString(), boundedLimit(limit))) {
            created.add(dispatchTask(userId, schedule));
        }
        return created;
    }

    /**
     * 跨 owner 查询到期计划并派发，供全局调度 Worker 使用。
     * @param limit 每轮最多处理计划数，限制在 1 到 20。
     * @return 每个计划对应的派发结果。
     */
    @Override
    @Transactional
    public List<Map<String, Object>> dispatchDueAll(int limit) {
        List<Map<String, Object>> created = new ArrayList<>();
        for (Map<String, Object> schedule : mapper.selectDueAll(boundedLimit(limit))) {
            UUID userId = UUID.fromString(String.valueOf(schedule.get("owner_user_id")));
            created.add(dispatchTask(userId, schedule));
        }
        return created;
    }

    /**
     * 将单条到期计划转换为任务，并推进计划的 next_run_at。
     * @param userId 计划所属 owner 的 UUID。
     * @param schedule Mapper 返回的计划行。
     * @return 计划名称、去重入队结果和任务快照。
     */
    private Map<String, Object> dispatchTask(UUID userId, Map<String, Object> schedule) {
        String scheduleId = String.valueOf(schedule.get("id"));
        long scheduledFor = Math.round(((Number) schedule.get("next_run_at")).doubleValue());
        String dedupe = "schedule:" + scheduleId + ":" + scheduledFor;
        TaskStore.EnqueueResult result = tasks.enqueue(
                userId,
                String.valueOf(schedule.get("task_type")),
                String.valueOf(schedule.get("lane")),
                payload(schedule),
                dedupe,
                "schedule",
                null
        );
        long now = java.time.Instant.now().getEpochSecond();
        long calculationBase = scheduledFor < now - Duration.ofDays(1).toSeconds() ? now : scheduledFor;
        double nextRun = nextRun(schedule, calculationBase);
        mapper.markDispatched(userId.toString(), scheduleId, nextRun, String.valueOf(result.task().get("id")));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("schedule", schedule.get("name"));
        item.put("queued", result.created());
        item.put("task", result.task());
        return item;
    }

    /**
     * 限制调度批次大小，避免单次事务派发过多任务。
     * @param limit 调度器请求的批次大小。
     * @return 1 到 20 范围内的批次大小。
     */
    private int boundedLimit(int limit) {
        return Math.max(1, Math.min(limit, 20));
    }

    /**
     * 取得计划 payload，兼容 Mapper 已解析的 Map 和旧的 payload_json 列。
     * @param schedule 计划数据库行。
     * @return 任务入队使用的 payload Map；字段缺失或不是对象时为空 Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> schedule) {
        Object value = schedule.get("payload");
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        Object raw = schedule.get("payload_json");
        Object parsed = parse(raw);
        return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /**
     * 根据计划类型计算下一次运行时间。
     * @param schedule 含 schedule_kind、schedule_value 和 timezone 的计划行。
     * @param after 本次运行的 Unix 秒时间。
     * @return 下一次运行的 Unix 秒时间；interval 按秒增加，daily 计算下一个本地时刻，
     *         其他类型按 60 秒推进。
     * @throws DateTimeException daily 时区或 HH:mm 值非法时抛出。
     */
    private double nextRun(Map<String, Object> schedule, double after) {
        String kind = String.valueOf(schedule.getOrDefault("schedule_kind", "cron"));
        String value = String.valueOf(schedule.getOrDefault("schedule_value", "60"));
        if ("interval".equals(kind)) return after + Math.max(1, Long.parseLong(value));
        if ("daily".equals(kind)) {
            ZoneId zone = ZoneId.of(String.valueOf(schedule.getOrDefault("timezone", "UTC")));
            ZonedDateTime current = ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond((long) after), zone);
            ZonedDateTime target = current.with(java.time.LocalTime.parse(value, DateTimeFormatter.ofPattern("HH:mm")));
            if (target.toEpochSecond() <= (long) after) target = target.plusDays(1);
            return target.toEpochSecond();
        }
        return after + 60;
    }

    /**
     * 将计划数据库行转换为 API 结构并解析 payload JSON。
     * @param row Mapper 返回的计划行；空行返回 {@code null}。
     * @return 包含计划配置、下次运行和最近任务 ID 的有序 Map。
     */
    private Map<String, Object> normalize(Map<String, Object> row) {
        if (row == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("name", row.get("name"));
        result.put("cron", row.get("cron"));
        result.put("schedule_kind", row.get("schedule_kind"));
        result.put("schedule_value", row.get("schedule_value"));
        result.put("task_type", row.get("task_type"));
        result.put("lane", row.get("lane"));
        result.put("payload", parse(row.get("payload_json")));
        result.put("enabled", Boolean.TRUE.equals(row.get("enabled")) || "t".equals(row.get("enabled")));
        result.put("priority", row.get("priority"));
        result.put("max_attempts", row.get("max_attempts"));
        result.put("timezone", row.get("timezone"));
        result.put("next_run_at", row.get("next_run_at"));
        result.put("last_task_id", row.get("last_task_id"));
        result.put("created_at", row.get("created_at"));
        result.put("updated_at", row.get("updated_at"));
        return result;
    }

    /**
     * 解析计划 payload JSON。
     * @param value JSON 文本。
     * @return 解析后的对象；空值或坏 JSON 返回空 Map。
     */
    private Object parse(Object value) {
        if (value == null) return Map.of();
        try { return objectMapper.readValue(String.valueOf(value), Object.class); }
        catch (JsonProcessingException error) { return Map.of(); }
    }

    /**
     * 将计划 payload 编码为 JSON。
     * @param value 任务 payload。
     * @return JSON 文本。
     * @throws IllegalArgumentException payload 无法编码时抛出。
     */
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("schedule payload must be JSON", error); }
    }

    /**
     * 校验计划操作的 owner 作用域。
     * @param userId 计划所属 owner 的 UUID。
     * @throws IllegalArgumentException userId 为空时抛出。
     */
    private static void requireUser(UUID userId) {
        if (userId == null) throw new IllegalArgumentException("userId must not be null");
    }
}
