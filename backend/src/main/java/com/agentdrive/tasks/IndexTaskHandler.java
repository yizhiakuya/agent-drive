package com.agentdrive.tasks;

import com.agentdrive.index.EmbeddingService;
import com.agentdrive.index.IndexingService;
import com.agentdrive.files.FileStorageService;
import com.agentdrive.progress.TaskProgressReporter;
import com.agentdrive.vision.VisionDescriptionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Worker 使用的任务分发器，负责领取任务、解析 payload，并把索引、向量化和自动化任务路由到对应服务。
 * 任务成功或失败都通过 {@link TaskWorkerStore} 完成状态迁移；单文件索引成功后才继续向量化，文件列表任务则允许单个文件失败后继续处理其余文件。
 */
@Service
@Profile({"java-files", "java-auth", "java-chat"})
public class IndexTaskHandler {
    private static final int TASK_LEASE_SECONDS = 300;
    private static final long PROGRESS_INTERVAL_NANOS = 250_000_000L;
    private final TaskWorkerStore workers;
    private final IndexingService indexing;
    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddings;
    private final AutomationTaskExecutor automation;
    private final VisionDescriptionService vision;
    private final FileStorageService files;
    private final TaskStore taskStore;
    private final ChatTaskExecutor chat;

    /**
     * 创建不带 embedding 和 automation 扩展的任务处理器，主要用于测试或仅启用全文索引的运行模式。
     *
     * @param workers 负责领取和完成任务的 Worker 状态存储。
     * @param indexing 执行文件全文抽取和索引替换的服务。
     * @param objectMapper 解析数据库中 JSON payload 的 Jackson mapper。
     */
    public IndexTaskHandler(TaskWorkerStore workers, IndexingService indexing, ObjectMapper objectMapper) {
        this(workers, indexing, objectMapper, null, (AutomationTaskExecutor) null, (VisionDescriptionService) null, null, null, null);
    }

    /**
     * 创建可执行全文索引和 embedding 的任务处理器，但不注入可选 automation 实现。
     *
     * @param workers 负责领取和完成任务的 Worker 状态存储。
     * @param indexing 执行文件全文抽取和索引替换的服务。
     * @param objectMapper 解析数据库中 JSON payload 的 Jackson mapper。
     * @param embeddings 执行分批 provider 请求并写回向量的服务。
     */
    public IndexTaskHandler(TaskWorkerStore workers, IndexingService indexing, ObjectMapper objectMapper,
                            EmbeddingService embeddings) {
        this(workers, indexing, objectMapper, embeddings, (AutomationTaskExecutor) null, (VisionDescriptionService) null, null, null, null);
    }

    /**
     * 创建完整任务处理器，并保存可选的 automation 执行器。
     * automation 为空时索引任务仍可运行，但收到 {@code automation.run} 会以失败状态结束。
     *
     * @param workers 负责领取和完成任务的 Worker 状态存储。
     * @param indexing 执行文件全文抽取和索引替换的服务。
     * @param objectMapper 解析数据库中 JSON payload 的 Jackson mapper。
     * @param embeddings 执行分批 provider 请求并写回向量的服务，可为 {@code null}。
     * @param automation 执行 automation payload 的可选实现，可为 {@code null}。
     */
    public IndexTaskHandler(TaskWorkerStore workers, IndexingService indexing, ObjectMapper objectMapper,
                            EmbeddingService embeddings, AutomationTaskExecutor automation) {
        this(workers, indexing, objectMapper, embeddings, automation, null, null, null, null);
    }

    /**
     * 创建完整索引任务处理器，并保存视觉描述扩展。
     * @param workers 负责任务租约和状态迁移的 Worker 存储。
     * @param indexing 执行全文或视觉描述索引替换的服务。
     * @param objectMapper 解析 payload 和序列化视觉描述的 JSON mapper。
     * @param embeddings 执行文本向量化的服务，可为空。
     * @param automation 执行自动化任务的服务，可为空。
     * @param vision 执行图片结构化识别的服务，可为空。
     */
    public IndexTaskHandler(TaskWorkerStore workers, IndexingService indexing, ObjectMapper objectMapper,
                            EmbeddingService embeddings, AutomationTaskExecutor automation,
                            VisionDescriptionService vision) {
        this(workers, indexing, objectMapper, embeddings, automation, vision, null, null, null);
    }

    /**
     * 保留旧的维护构造器，未启用后台聊天时使用空执行器。
     */
    public IndexTaskHandler(TaskWorkerStore workers, IndexingService indexing, ObjectMapper objectMapper,
                            EmbeddingService embeddings, AutomationTaskExecutor automation,
                            VisionDescriptionService vision, FileStorageService files, TaskStore taskStore) {
        this(workers, indexing, objectMapper, embeddings, automation, vision, files, taskStore, null);
    }

    /**
     * 创建包含每日维护依赖的完整任务处理器。
     *
     * @param workers 负责任务租约和状态迁移的 Worker 存储。
     * @param indexing 执行全文、向量前置索引和失效索引清理的服务。
     * @param objectMapper 解析任务 payload 的 JSON mapper。
     * @param embeddings 执行文本向量化的服务，可为空。
     * @param automation 执行自动化任务的服务，可为空。
     * @param vision 执行图片结构化识别的服务，可为空。
     * @param files 执行回收站保留期清理的 owner 文件服务。
     * @param taskStore 执行终态任务历史清理的 owner 任务存储。
     * @param chat 执行 chat.run 的可选后台聊天执行器。
     */
    public IndexTaskHandler(TaskWorkerStore workers, IndexingService indexing, ObjectMapper objectMapper,
                            EmbeddingService embeddings, AutomationTaskExecutor automation,
                            VisionDescriptionService vision, FileStorageService files, TaskStore taskStore,
                            ChatTaskExecutor chat) {
        this.workers = workers;
        this.indexing = indexing;
        this.objectMapper = objectMapper;
        this.embeddings = embeddings;
        this.automation = automation;
        this.vision = vision;
        this.files = files;
        this.taskStore = taskStore;
        this.chat = chat;
    }

    /**
     * Spring 注入入口；从 {@link ObjectProvider} 获取可选 automation 实例并转交给完整构造器。
     * 使用 provider 是为了在没有 automation bean 的 profile 中仍能创建 Worker。
     *
     * @param workers 负责领取和完成任务的 Worker 状态存储。
     * @param indexing 执行文件全文抽取和索引替换的服务。
     * @param objectMapper 解析数据库中 JSON payload 的 Jackson mapper。
     * @param embeddings 执行分批 provider 请求并写回向量的服务。
     * @param automation 可选 automation bean 的 provider。
     */
    @Autowired
    public IndexTaskHandler(TaskWorkerStore workers, IndexingService indexing, ObjectMapper objectMapper,
                            EmbeddingService embeddings, ObjectProvider<AutomationTaskExecutor> automation,
                            ObjectProvider<VisionDescriptionService> vision, FileStorageService files,
                            TaskStore taskStore, ObjectProvider<ChatTaskExecutor> chat) {
        this(workers, indexing, objectMapper, embeddings, automation.getIfAvailable(), vision.getIfAvailable(),
                files, taskStore, chat.getIfAvailable());
    }

    /**
     * 回收过期租约，并按 {@code index}、{@code default}、{@code maintenance}、{@code automation} 的顺序最多领取并执行一条任务。
     * 领取使用 300 秒租约；没有任务时返回 {@code false}，领取到任务后即使任务失败也返回 {@code true}，因为失败状态已由 {@link #execute(String, Map)} 记录。
     *
     * @param workerId 当前 Worker 的唯一标识。
     * @return 本轮是否领取并处理了一条任务。
     */
    public boolean runOnce(String workerId) {
        workers.recoverExpiredLeases();
        for (String lane : new String[]{"index", "default", "maintenance", "automation"}) {
            Map<String, Object> task = workers.claim(workerId, lane, 300);
            if (task == null || task.isEmpty()) continue;
            execute(workerId, task);
            return true;
        }
        return false;
    }

    /**
     * 根据任务类型执行一次已领取任务，并把异常转换为 Worker 失败迁移。
     * 支持 {@code index.file}、{@code index.rebuild}、{@code index.embed}、{@code index.vision}、
     * {@code index.cleanup}、{@code maintenance.daily} 和 {@code automation.run}；
     * 不支持的类型、坏 owner UUID 或坏 payload 都不会向调度线程继续抛出，而会调用 {@code fail}。
     *
     * @param workerId 持有该任务租约的 Worker ID。
     * @param task 任务存储返回的完整任务记录，至少包含 id、user_id、type 和 payload_json。
     */
    private void execute(String workerId, Map<String, Object> task) {
        String taskId = String.valueOf(task.get("id"));
        ProgressReporter progress = new ProgressReporter(workerId, taskId);
        try {
            UUID userId = UUID.fromString(String.valueOf(task.get("user_id")));
            Map<String, Object> payload = payload(task.get("payload_json"));
            String type = String.valueOf(task.get("type"));
            progress.report(0, 0, "正在执行：" + type);
            Map<String, Object> result = switch (type) {
                case "index.file" -> indexFile(userId, required(payload, "path"), booleanValue(payload, "force"), progress);
                case "index.rebuild" -> {
                    Map<String, Object> rebuilt = new LinkedHashMap<>(indexing.rebuild(
                            userId, string(payload, "prefix", null), progress));
                    progress.reportNow(0, 0, "全文索引完成（" + rebuilt.getOrDefault("processed_files", 0)
                            + " 个文件），开始向量化");
                    if (embeddings != null) {
                        rebuilt.put("embedding", requireEmbeddingSuccess(embeddings.embed(userId, List.of(), 64,
                                booleanValue(payload, "force"), progress), true));
                    }
                    yield rebuilt;
                }
                case "index.embed" -> embedFiles(userId, optionalFiles(payload), booleanValue(payload, "force"), progress);
                case "index.vision" -> visionFiles(userId, optionalFiles(payload), booleanValue(payload, "force"), progress);
                case "index.cleanup" -> {
                    progress.report(0, 1, "正在清理失效索引");
                    Map<String, Object> cleanup = indexing.cleanup(userId);
                    progress.reportNow(1, 1, "失效索引清理完成");
                    yield cleanup;
                }
                case "maintenance.daily" -> dailyMaintenance(userId, payload, progress);
                case "automation.run" -> {
                    if (automation == null) throw new IllegalStateException("automation handler unavailable");
                    yield automation.execute(userId, payload);
                }
                case "chat.run" -> {
                    if (chat == null) throw new IllegalStateException("chat handler unavailable");
                    yield chat.execute(userId, payload, progress);
                }
                default -> throw new IllegalArgumentException("unsupported task type: " + type);
            };
            progress.reportNow(1, 1, "任务执行完成");
            if (!workers.succeed(workerId, taskId, result)) {
                workers.fail(workerId, taskId, "task completion rejected");
            }
        } catch (Exception error) {
            if (isInterrupted(error)) Thread.currentThread().interrupt();
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            try {
                progress.reportNow(0, 0, "任务执行失败：" + message);
            } catch (TaskExecutionStoppedException ignored) {
                // 租约或取消状态已阻止进度写入，直接交给 fail 完成取消/租约保护迁移。
            }
            workers.fail(workerId, taskId, message);
        }
    }

    /**
     * 执行每日维护的三个独立步骤：失效索引、过期回收站和终态任务历史。
     * 每一步都使用 owner 范围，只有全部完成后任务才会写入成功状态；空的可选依赖表示
     * 当前运行 profile 不支持维护，会让任务明确失败而不是伪报成功。
     *
     * @param userId 维护任务归属 owner 的 UUID。
     * @param payload 维护参数；可选 {@code trash_retention_days} 覆盖默认 30 天。
     * @return 三个维护步骤的结果摘要。
     */
    private Map<String, Object> dailyMaintenance(UUID userId, Map<String, Object> payload,
                                                  TaskProgressReporter progress) {
        if (files == null || taskStore == null) {
            throw new IllegalStateException("maintenance handler unavailable");
        }
        int retentionDays = integerValue(payload, "trash_retention_days", 30);
        Map<String, Object> result = new LinkedHashMap<>();
        progress.report(0, 3, "系统维护：清理失效索引");
        result.put("index", indexing.cleanup(userId));
        progress.report(1, 3, "系统维护：清理回收站");
        Map<String, Object> trash = files.cleanupTrash(userId, retentionDays);
        result.put("trash_removed", trash == null ? 0 : trash.getOrDefault("removed", 0));
        progress.report(2, 3, "系统维护：清理任务历史");
        result.put("task_history", taskStore.pruneHistory(userId, 30, 2000));
        progress.report(3, 3, "系统维护：全部步骤完成");
        return result;
    }

    /**
     * 执行单文件全文索引，并在全文成功写入后为该文件调用 embedding 服务。
     * 全文抽取失败时不会调用 provider；结果 map 保留全文结果并在成功时追加 {@code embedding} 统计。
     *
     * @param userId 文件归属用户的 UUID。
     * @param path 要索引的用户相对文件路径。
     * @param force 是否包含并覆盖该文件已有向量。
     * @return 全文索引结果，可能带有向量化结果。
     */
    private Map<String, Object> indexFile(UUID userId, String path, boolean force,
                                           TaskProgressReporter progress) {
        progress.report(0, 2, "文件索引：正在抽取 " + path);
        Map<String, Object> result = new LinkedHashMap<>(indexing.indexFile(userId, path));
        if (embeddings != null && Boolean.TRUE.equals(result.get("indexed"))) {
            progress.reportNow(1, 2, "文件索引：文本已完成，正在生成向量");
            result.put("embedding", requireEmbeddingSuccess(
                    embeddings.embed(userId, List.of(path), 64, force, progress), true));
        } else {
            progress.reportNow(2, 2, "文件索引：已跳过 " + path);
        }
        if (Boolean.TRUE.equals(result.get("indexed"))) progress.reportNow(2, 2, "文件索引：已完成 " + path);
        return result;
    }

    /**
     * 先逐个刷新文件全文索引，再对整个路径列表执行一次分批向量化。
     * 单个文件抽取异常会被记录在该文件的结果中并继续处理；embedding provider 的失败会抛给任务状态机进入 fail/retry。
     *
     * @param userId 文件归属用户的 UUID。
     * @param paths 已规范化的用户相对文件路径列表。
     * @param force 是否包含并覆盖这些文件已有向量。
     * @return 包含逐文件全文结果和整体 embedding 结果的 map。
     */
    private Map<String, Object> embedFiles(UUID userId, List<String> paths, boolean force,
                                            TaskProgressReporter progress) {
        if (embeddings == null) {
            throw new IllegalStateException("embedding_failed: embedding_handler_unavailable");
        }
        List<Map<String, Object>> indexed = new ArrayList<>();
        int total = paths.size();
        if (total == 0) progress.report(0, 0, "文件向量化：准备处理全部文件");
        int processed = 0;
        for (String path : paths) {
            progress.report(processed, total, "文件向量化：正在索引 " + path);
            try {
                indexed.add(indexing.indexFile(userId, path));
            } catch (Exception error) {
                rethrowIfInterrupted(error, "embedding_interrupted");
                Map<String, Object> skipped = new LinkedHashMap<>();
                skipped.put("path", path);
                skipped.put("indexed", false);
                skipped.put("status", "error");
                skipped.put("error", error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage());
                indexed.add(skipped);
            }
            processed++;
            progress.report(processed, total, "文件向量化：已完成全文索引 " + processed + "/" + total);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", indexed);
        progress.reportNow(0, 0, "文件向量化：全文索引完成，开始生成向量");
        result.put("embedding", requireEmbeddingSuccess(
                embeddings.embed(userId, paths, 64, force, progress), false));
        return result;
    }

    /**
     * 为图片生成结构化描述、写入当前 revision 文档，并复用文本 embedding 服务生成向量。
     * 单个图片失败会保留错误并继续处理其余路径；描述正文不会直接写入任务日志。
     *
     * @param userId 图片归属用户 UUID。
     * @param paths 待识别的 owner 相对图片路径列表。
     * @param force 是否强制清理并重算已有向量。
     * @return 每个图片的处理状态和整体 embedding 统计。
     */
    private Map<String, Object> visionFiles(UUID userId, List<String> paths, boolean force,
                                            TaskProgressReporter progress) {
        if (vision == null) throw new IllegalStateException("vision_handler_unavailable");
        if (paths.isEmpty()) throw new IllegalArgumentException("vision_files_required");
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> indexedPaths = new ArrayList<>();
        int processed = 0;
        for (String path : paths) {
            progress.report(processed, paths.size(), "图片索引：正在识别 " + path);
            try {
                Map<String, Object> described = vision.describeFile(userId, path);
                String json = objectMapper.writeValueAsString(described.get("description"));
                Map<String, Object> indexed = new LinkedHashMap<>(indexing.indexDescription(userId, path, json));
                indexed.put("model", described.get("model"));
                results.add(indexed);
                if (Boolean.TRUE.equals(indexed.get("indexed"))) indexedPaths.add(path);
            } catch (Exception error) {
                rethrowIfInterrupted(error, "vision_interrupted");
                Map<String, Object> skipped = new LinkedHashMap<>();
                skipped.put("path", path);
                skipped.put("indexed", false);
                skipped.put("status", "error");
                skipped.put("error", error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage());
                results.add(skipped);
            }
            processed++;
            progress.report(processed, paths.size(), "图片索引：已完成视觉识别 " + processed + "/" + paths.size());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", results);
        result.put("described", indexedPaths.size());
        if (indexedPaths.isEmpty()) throw new IllegalStateException("vision_all_files_failed");
        if (embeddings == null) throw new IllegalStateException("embedding_failed: embedding_handler_unavailable");
        progress.reportNow(0, 0, "图片索引：视觉识别完成，开始生成向量");
        result.put("embedding", requireEmbeddingSuccess(
                embeddings.embed(userId, indexedPaths, 64, force, progress), false));
        return result;
    }

    /** 将 embedding 服务的结果语义转换为任务状态；仅全文索引允许“未配置”降级。 */
    private Map<String, Object> requireEmbeddingSuccess(Map<String, Object> result,
                                                         boolean allowNotConfigured) {
        if (result != null && Boolean.TRUE.equals(result.get("vectorized"))) return result;
        String reason = result == null ? "embedding_result_missing"
                : String.valueOf(result.getOrDefault("reason", "embedding_failed"));
        if (allowNotConfigured && "embedding_not_configured".equals(reason)) return result;
        throw new IllegalStateException("embedding_failed: " + reason);
    }

    /** 中断不能被逐文件容错吞掉，否则停机后任务会继续调用 provider。 */
    private static void rethrowIfInterrupted(Exception error, String message) {
        if (!isInterrupted(error)) return;
        Thread.currentThread().interrupt();
        throw new IllegalStateException(message, error);
    }

    private static boolean isInterrupted(Throwable error) {
        if (Thread.currentThread().isInterrupted()) return true;
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException) return true;
        }
        return false;
    }

    /**
     * 把业务服务的阶段回调限速为每 250ms 至多一次，并把每次有效更新续租任务。
     * 阶段完成或任务失败会立即写入，保证详情不会停在最后一个中间批次。
     */
    private final class ProgressReporter implements TaskProgressReporter {
        private final String workerId;
        private final String taskId;
        private long lastReportNanos = Long.MIN_VALUE;
        private int lastCurrent = Integer.MIN_VALUE;
        private int lastTotal = Integer.MIN_VALUE;
        private String lastMessage;

        private ProgressReporter(String workerId, String taskId) {
            this.workerId = workerId;
            this.taskId = taskId;
        }

        @Override
        public void report(int current, int total, String message) {
            write(current, total, message, false);
        }

        @Override
        public void reportNow(int current, int total, String message) {
            write(current, total, message, true);
        }

        @Override
        public void heartbeat() {
            if (!workers.heartbeat(workerId, taskId, TASK_LEASE_SECONDS)) {
                throw new TaskExecutionStoppedException("task lease lost or cancellation requested");
            }
        }

        private void write(int current, int total, String message, boolean immediate) {
            String safeMessage = message == null || message.isBlank() ? "处理中" : message;
            if (current == lastCurrent && total == lastTotal && safeMessage.equals(lastMessage)) return;
            long now = System.nanoTime();
            if (!immediate && lastReportNanos != Long.MIN_VALUE
                    && now - lastReportNanos < PROGRESS_INTERVAL_NANOS) return;
            if (!workers.updateProgress(workerId, taskId, current, total, safeMessage, TASK_LEASE_SECONDS)) {
                throw new TaskExecutionStoppedException("task lease lost or cancellation requested");
            }
            lastReportNanos = now;
            lastCurrent = current;
            lastTotal = total;
            lastMessage = safeMessage;
        }
    }

    /** 进度写入被状态机拒绝时停止当前处理器，防止取消后的工作继续执行。 */
    private static final class TaskExecutionStoppedException extends RuntimeException {
        /**
         * 创建携带稳定停止原因的处理器内部异常。
         * @param message 租约丢失或取消请求说明。
         */
        private TaskExecutionStoppedException(String message) {
            super(message);
        }
    }

    /**
     * 将任务 payload 解析为字符串键 map。
     * 已经是 map 的值直接复用；字符串会按 JSON 解析；空值或 JSON 顶层不是对象时使用空 map。
     *
     * @param raw 数据库返回的 payload map、JSON 字符串或空值。
     * @return 任务参数 map；不会复制已有 map。
     * @throws IllegalArgumentException JSON 无法解析时抛出。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Object raw) {
        if (raw instanceof Map<?, ?> map) return (Map<String, Object>) map;
        if (raw == null) return Map.of();
        try {
            Object parsed = objectMapper.readValue(String.valueOf(raw), Object.class);
            return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("task payload is not valid JSON", error);
        }
    }

    /**
     * 从任务 payload 读取一个必填的非空字符串字段。
     *
     * @param payload 已解析的任务参数。
     * @param name 必填字段名，例如 {@code path}。
     * @return 字段的字符串值。
     * @throws IllegalArgumentException 字段缺失或转换后为空白时抛出。
     */
    private String required(Map<String, Object> payload, String name) {
        String value = string(payload, name, "");
        if (value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    /**
     * 读取可选 payload 字段并转换为字符串。
     * 字段不存在或值为 {@code null} 时返回调用方提供的默认值。
     *
     * @param payload 已解析的任务参数。
     * @param name 要读取的字段名。
     * @param fallback 字段缺失时的默认值。
     * @return 字段字符串或默认值。
     */
    private String string(Map<String, Object> payload, String name, String fallback) {
        Object value = payload.get(name);
        return value == null ? fallback : String.valueOf(value);
    }

    /**
     * 读取 payload 中的布尔开关。
     * JSON Boolean 直接使用，其他值按 {@link Boolean#parseBoolean(String)} 解析，缺失值因此默认为 {@code false}。
     *
     * @param payload 已解析的任务参数。
     * @param name 布尔字段名，例如 {@code force}。
     * @return 解析后的开关值。
     */
    private boolean booleanValue(Map<String, Object> payload, String name) {
        Object value = payload.get(name);
        return value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * 读取有上下限的整数维护参数。
     * 缺失、格式错误或超出范围的值回退到调用方提供的默认值，避免任务 payload 让维护窗口失控。
     *
     * @param payload 已解析的任务参数。
     * @param name 整数字段名。
     * @param fallback 缺失或非法时的默认值。
     * @return 1 到 3650 之间的整数值。
     */
    private int integerValue(Map<String, Object> payload, String name, int fallback) {
        Object value = payload.get(name);
        try {
            int parsed = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
            return Math.max(1, Math.min(parsed, 3650));
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    /**
     * 从 embedding 任务 payload 读取并规范化 {@code files} 列表。
     * 缺少字段表示不限制文件范围；存在字段时要求是列表，并交给 {@link IndexTaskPaths} 处理字符串类型、路径安全、去重和 1000 项上限。
     *
     * @param payload 已解析的任务参数。
     * @return 规范化后的不可变路径列表；未提供 files 时为空列表。
     * @throws IllegalArgumentException files 不是列表或包含非法路径时抛出。
     */
    private List<String> optionalFiles(Map<String, Object> payload) {
        Object raw = payload.get("files");
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> values)) {
            throw new IllegalArgumentException("files must be a list");
        }
        return IndexTaskPaths.normalize(values);
    }
}
