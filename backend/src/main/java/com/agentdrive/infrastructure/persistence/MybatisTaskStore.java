package com.agentdrive.infrastructure.persistence;

import com.agentdrive.index.EmbeddingFingerprint;
import com.agentdrive.index.EmbeddingRuntimeConfig;
import com.agentdrive.index.IndexStore;
import com.agentdrive.infrastructure.persistence.mapper.TaskMapper;
import com.agentdrive.tasks.TaskStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 通过 MyBatis 管理 owner-scoped 持久任务及任务事件。
 * <p>列表和事件读取限制返回数量；入队在事务内执行 active dedupe 查询/插入和 queued 或
 * deduplicated 事件写入；cancel/retry 也把状态变更与事件放在同一事务中。</p>
 */
public class MybatisTaskStore implements TaskStore {
    private static final long INDEX_STATS_CACHE_NANOS = TimeUnit.SECONDS.toNanos(15);
    private final TaskMapper mapper;
    private final ObjectMapper objectMapper;
    private final IndexStore index;
    private final EmbeddingRuntimeConfig embeddingConfigs;
    private final ConcurrentHashMap<UUID, CachedIndexStats> indexStatsCache = new ConcurrentHashMap<>();

    /**
     * 保存任务 Mapper 和 JSON 映射器。
     * @param mapper 读写任务、子任务汇总和事件表的 Mapper。
     * @param objectMapper 编解码任务 payload、result 和 event data 的映射器。
     */
    public MybatisTaskStore(TaskMapper mapper, ObjectMapper objectMapper) {
        this(mapper, objectMapper, null, null);
    }

    /**
     * 创建带索引总览统计的任务存储。
     * @param mapper 读写任务、子任务汇总和事件表的 Mapper。
     * @param objectMapper 编解码任务 payload、result 和 event data 的映射器。
     * @param index 读取 owner 全盘索引统计的存储；为空时保留兼容的空统计。
     * @param embeddingConfigs 读取当前 owner embedding 配置并生成有效性指纹的端口。
     */
    public MybatisTaskStore(TaskMapper mapper, ObjectMapper objectMapper,
                            IndexStore index, EmbeddingRuntimeConfig embeddingConfigs) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.index = index;
        this.embeddingConfigs = embeddingConfigs;
    }

    /**
     * 按 owner、状态、类型和分页条件查询任务。
     * @param userId 任务所属 owner 的 UUID。
     * @param statuses 可选状态过滤列表。
     * @param type 可选任务类型过滤。
     * @param includeChildren 是否将子任务纳入查询。
     * @param limit 请求条数，最终限制在 1 到 201；201 供列表 API 多取一条判断 {@code has_more}。
     * @param offset 分页偏移，负值按 0 处理。
     * @return 统一字段名并解析 JSON payload/result 的任务列表。
     */
    @Override
    public List<Map<String, Object>> list(UUID userId, List<String> statuses, String type,
                                          boolean includeChildren, int limit, int offset) {
        requireUser(userId);
        return mapper.selectTasks(userId.toString(), statuses, type, includeChildren,
                        Math.max(1, Math.min(limit, 201)), Math.max(0, offset))
                .stream().map(this::task).toList();
    }

    /**
     * 汇总 owner 的任务状态数量，并读取独立 Worker 心跳表计算在线数量。
     * 空闲 Worker 也会被计入；只有最近 10 秒没有心跳的进程才会被视为离线。
     * @param userId 任务所属 owner 的 UUID。
     * @return 包含 counts、workers 和 index 摘要的 Map。
     */
    @Override
    public Map<String, Object> overview(UUID userId) {
        requireUser(userId);
        Map<String, Object> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : mapper.selectStatusCounts(userId.toString())) {
            counts.put(String.valueOf(row.get("status")), row.get("count"));
        }
        int workerCount = Math.max(0, mapper.selectOnlineWorkerCount());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("counts", counts);
        result.put("workers", Map.of("online", workerCount > 0, "count", workerCount));
        result.put("index", indexOverview(userId));
        return result;
    }

    /**
     * 清除 owner 的短期索引统计缓存，使文件变更后下次概览立即读取数据库。
     * @param userId 发生文件变更的 owner UUID。
     */
    @Override
    public void invalidateOverview(UUID userId) {
        if (userId != null) indexStatsCache.remove(userId);
    }

    /**
     * 查询 owner 可见的单个任务。
     * @param userId 任务所属 owner 的 UUID。
     * @param taskId 要查询的任务 UUID。
     * @return 规范化任务 Map；数据库没有匹配行时为 {@code null}。
     */
    @Override
    public Map<String, Object> get(UUID userId, UUID taskId) {
        requireUser(userId);
        return task(mapper.selectTask(userId.toString(), taskId.toString()));
    }

    /**
     * 查询父任务的子任务状态摘要。
     * @param userId 父任务所属 owner 的 UUID。
     * @param parentId 父任务 UUID。
     * @return 规范化后的子任务列表。
     */
    @Override
    public List<Map<String, Object>> childSummary(UUID userId, UUID parentId) {
        requireUser(userId);
        return mapper.selectChildSummary(userId.toString(), parentId.toString()).stream()
                .map(this::task).toList();
    }

    /**
     * 在事务内创建任务或复用相同 active dedupe key 的任务，并记录事件。
     * @param userId 任务所属 owner 的 UUID。
     * @param type 任务类型。
     * @param lane Worker 领取任务的 lane。
     * @param payload 任务输入对象，会序列化为 JSON。
     * @param dedupeKey 活跃任务去重键；为空时每次创建新任务。
     * @param origin 任务来源；为空使用 {@code api}。
     * @param parentId 父任务 UUID；顶层任务可为空。
     * @return 任务快照和是否新创建的结果。
     * @throws IllegalStateException 数据库既未创建也未找到可复用任务时抛出。
     */
    @Override
    @Transactional
    public EnqueueResult enqueue(UUID userId, String type, String lane, Map<String, Object> payload,
                                 String dedupeKey, String origin, UUID parentId) {
        requireUser(userId);
        String serialized = json(payload == null ? Map.of() : payload);
        Map<String, Object> row = mapper.insertTask(
                userId.toString(), parentId == null ? null : parentId.toString(), type, lane,
                dedupeKey, serialized, origin == null ? "api" : origin
        );
        boolean created = row != null;
        if (!created && dedupeKey != null) {
            row = mapper.selectActiveByDedupe(userId.toString(), dedupeKey);
        }
        if (row == null) {
            throw new IllegalStateException("task enqueue did not return a task");
        }
        String taskId = String.valueOf(row.get("id"));
        mapper.insertEvent(taskId, created ? "queued" : "deduplicated", "{}");
        return new EnqueueResult(task(row), created);
    }

    /**
     * 请求取消 owner 的任务并追加 cancel_requested 事件。
     * @param userId 任务所属 owner 的 UUID。
     * @param taskId 要取消的任务 UUID。
     * @return 更新后的任务快照；任务不存在时为 {@code null}。
     */
    @Override
    @Transactional
    public Map<String, Object> cancel(UUID userId, UUID taskId) {
        requireUser(userId);
        Map<String, Object> before = mapper.selectTask(userId.toString(), taskId.toString());
        if (before == null) return null;
        mapper.cancelTask(userId.toString(), taskId.toString());
        mapper.insertEvent(taskId.toString(), "cancel_requested", "{}");
        return task(mapper.selectTask(userId.toString(), taskId.toString()));
    }

    /**
     * 仅对 failed/cancelled 任务执行重试转换并追加 retried 事件。
     * @param userId 任务所属 owner 的 UUID。
     * @param taskId 要重试的任务 UUID。
     * @return 重试后的任务快照；状态不允许重试或更新未命中时为 {@code null}。
     */
    @Override
    @Transactional
    public Map<String, Object> retry(UUID userId, UUID taskId) {
        requireUser(userId);
        Map<String, Object> before = mapper.selectTask(userId.toString(), taskId.toString());
        if (before == null || !("failed".equals(before.get("status")) || "cancelled".equals(before.get("status")))) {
            return null;
        }
        if (mapper.retryTask(userId.toString(), taskId.toString()) == 0) return null;
        mapper.insertEvent(taskId.toString(), "retried", "{}");
        return task(mapper.selectTask(userId.toString(), taskId.toString()));
    }

    /**
     * 读取 owner 任务事件流的最新 ID，供 SSE 增量订阅建立游标。
     * @param userId 任务所属 owner 的 UUID。
     * @return 最新事件 ID；没有事件时为 0。
     */
    @Override
    public long latestEventId(UUID userId) {
        requireUser(userId);
        Long value = mapper.latestEventId(userId.toString());
        return value == null ? 0L : value;
    }

    /**
     * 读取 owner 在指定事件 ID 之后的新事件。
     * @param userId 任务所属 owner 的 UUID。
     * @param afterId 已消费的事件 ID；负值按 0 处理。
     * @param limit 最大返回数量，最终限制在 1 到 500。
     * @return 解析 payload 后的增量事件列表。
     */
    @Override
    public List<Map<String, Object>> events(UUID userId, long afterId, int limit) {
        requireUser(userId);
        return mapper.selectEvents(userId.toString(), Math.max(0, afterId), Math.min(Math.max(1, limit), 500))
                .stream().map(this::event).toList();
    }

    /**
     * 删除 owner 中超过保留期且不再被父子任务关系保护的终态任务。
     *
     * <p>Mapper 使用递归 CTE 完成候选筛选和父任务保护，删除任务时由 PostgreSQL
     * 级联删除对应事件；这保证每日维护不会先删父任务再留下悬空的子任务历史。</p>
     *
     * @param userId 任务归属 owner 的 UUID。
     * @param olderThanDays 终态任务保留天数。
     * @param keepRecent 最近至少保留的终态任务数量。
     * @return 删除任务数量及当前实现兼容的事件/Worker 计数字段。
     */
    @Override
    @Transactional
    public Map<String, Object> pruneHistory(UUID userId, int olderThanDays, int keepRecent) {
        requireUser(userId);
        int days = Math.max(1, Math.min(olderThanDays, 3650));
        int recent = Math.max(1, Math.min(keepRecent, 100_000));
        double cutoff = Instant.now().minus(days, ChronoUnit.DAYS).toEpochMilli() / 1000.0;
        int jobs = mapper.pruneHistory(userId.toString(), cutoff, recent);
        return Map.of("jobs", jobs, "events", 0, "workers", 0,
                "older_than_days", days, "keep_recent", recent);
    }

    /**
     * 把任务数据库行映射为 API/Worker 共用的任务结构，并解析 JSON 字段。
     * @param row Mapper 返回的任务行；空行返回 {@code null}。
     * @return 包含状态、租约进度、父子关系和时间字段的有序 Map。
     */
    private Map<String, Object> task(Map<String, Object> row) {
        if (row == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("type", row.get("type"));
        result.put("lane", row.get("lane"));
        result.put("status", row.get("status"));
        result.put("result", parse(row.get("result_json")));
        result.put("error", row.get("error"));
        result.put("priority", row.get("priority"));
        result.put("resource_key", row.get("resource_key"));
        result.put("parent_id", row.get("parent_id"));
        result.put("origin", row.get("origin"));
        result.put("attempts", row.get("attempts"));
        result.put("max_attempts", row.get("max_attempts"));
        result.put("cancel_requested", Boolean.TRUE.equals(row.get("cancel_requested")) || "t".equals(row.get("cancel_requested")));
        result.put("progress", Map.of(
                "current", row.get("progress_current"),
                "total", row.get("progress_total"),
                "message", row.get("progress_message")
        ));
        result.put("created_at", row.get("created_at"));
        result.put("updated_at", row.get("updated_at"));
        result.put("started_at", row.get("started_at"));
        result.put("finished_at", row.get("finished_at"));
        if (row.containsKey("payload_json")) result.put("payload", parse(row.get("payload_json")));
        return result;
    }

    /**
     * 把任务事件数据库行映射为 SSE 可发送的事件结构。
     * @param row Mapper 返回的事件行。
     * @return 包含事件 ID、job ID、类型、解析后的 data 和创建时间的 Map。
     */
    private Map<String, Object> event(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("job_id", row.get("job_id"));
        result.put("type", row.get("type"));
        result.put("data", parse(row.get("payload_json")));
        result.put("created_at", row.get("created_at"));
        return result;
    }

    /**
     * 解析数据库 JSON 字段，坏 JSON 统一转换为空对象。
     * @param value JSON 文本、Map、List 或空值。
     * @return 解析后的对象；空值返回 {@code null}，解析失败返回空 Map。
     */
    private Object parse(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> || value instanceof List<?>) return value;
        try {
            return objectMapper.readValue(String.valueOf(value), Object.class);
        } catch (JsonProcessingException error) {
            return Map.of();
        }
    }

    /**
     * 组装任务中心使用的索引概览；全盘统计最多缓存 15 秒，任务状态计数仍每次实时读取。
     * @param userId 当前 owner UUID。
     * @return 含索引统计、embedding 配置状态和模型名的映射。
     */
    private Map<String, Object> indexOverview(UUID userId) {
        Optional<EmbeddingRuntimeConfig.Config> config = embeddingConfigs == null
                ? Optional.empty() : embeddingConfigs.find(userId);
        boolean configured = config.isPresent()
                && config.get().apiKey() != null
                && !config.get().apiKey().isBlank();
        String fingerprint = configured
                ? EmbeddingFingerprint.of(config.get().provider(), config.get().baseUrl(), config.get().model())
                : null;

        Map<String, Object> result = new LinkedHashMap<>();
        if (index != null) {
            result.putAll(cachedStats(userId, fingerprint).asMap());
        }
        result.put("embedding_configured", configured);
        result.put("model", config.map(EmbeddingRuntimeConfig.Config::model).orElse(""));
        return result;
    }

    /** 读取或刷新 owner 的短期索引统计缓存。 */
    private IndexStore.Stats cachedStats(UUID userId, String fingerprint) {
        long now = System.nanoTime();
        CachedIndexStats cached = indexStatsCache.get(userId);
        if (cached != null && cached.expiresAtNanos() > now
                && Objects.equals(cached.fingerprint(), fingerprint)) {
            return cached.stats();
        }
        IndexStore.Stats stats = index.statistics(userId, fingerprint);
        indexStatsCache.put(userId, new CachedIndexStats(fingerprint, stats, now + INDEX_STATS_CACHE_NANOS));
        return stats;
    }

    /**
     * 序列化任务 payload。
     * @param value 要写入任务表的输入对象。
     * @return JSON 文本。
     * @throws IllegalArgumentException 输入无法编码为 JSON 时抛出。
     */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("task payload must be JSON", error);
        }
    }

    /**
     * 阻止无 owner 的任务 SQL。
     * @param userId 任务所属 owner 的 UUID。
     * @throws IllegalArgumentException userId 为空时抛出。
     */
    private static void requireUser(UUID userId) {
        if (userId == null) throw new IllegalArgumentException("userId must not be null");
    }

    /** 一条带配置指纹和过期时间的索引统计缓存项。 */
    private record CachedIndexStats(String fingerprint, IndexStore.Stats stats, long expiresAtNanos) {
    }
}
