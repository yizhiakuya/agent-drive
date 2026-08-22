package com.agentdrive.index;

import com.agentdrive.vision.VisionDescriptionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 索引业务资源的 owner-scoped 应用服务。
 *
 * <p>这里定义的是正常业务操作：查询索引状态、写入单文件文档、写入视觉描述、
 * 向量化、清空向量和清理失效索引。HTTP Controller 与 Agent operation 共享这条
 * 边界；是否由调用方选择任务模块执行，不在 Agent 适配层硬编码。</p>
 */
@Service
@Profile({"java-files", "java-auth", "java-chat"})
public final class IndexDomainService {
    private final IndexStore index;
    private final IndexingService indexing;
    private final EmbeddingService embeddings;
    private final EmbeddingRuntimeConfig embeddingConfig;
    private final VisionDescriptionService vision;
    private final ObjectMapper objectMapper;
    private final IndexExecutionMonitor monitor;

    public IndexDomainService(IndexStore index,
                              IndexingService indexing,
                              EmbeddingService embeddings,
                              EmbeddingRuntimeConfig embeddingConfig,
                              VisionDescriptionService vision,
                              ObjectMapper objectMapper) {
        this(index, indexing, embeddings, embeddingConfig, vision, objectMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public IndexDomainService(IndexStore index,
                              IndexingService indexing,
                              EmbeddingService embeddings,
                              EmbeddingRuntimeConfig embeddingConfig,
                              VisionDescriptionService vision,
                              ObjectMapper objectMapper,
                              IndexExecutionMonitor monitor) {
        this.index = index;
        this.indexing = indexing;
        this.embeddings = embeddings;
        this.embeddingConfig = embeddingConfig;
        this.vision = vision;
        this.objectMapper = objectMapper;
        this.monitor = monitor;
    }

    /** 返回当前 owner 的索引统计和路径范围内的文件索引资源。 */
    public Map<String, Object> overview(UUID userId, String prefix, int limit) {
        requireUser(userId);
        String normalizedPrefix = normalizePrefix(prefix);
        Optional<EmbeddingRuntimeConfig.Config> configured = embeddingConfig.find(userId);
        boolean configuredWithKey = configured.isPresent()
                && configured.get().apiKey() != null && !configured.get().apiKey().isBlank();
        String fingerprint = configuredWithKey
                ? EmbeddingFingerprint.of(configured.get().provider(), configured.get().baseUrl(), configured.get().model())
                : null;
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> files = index.files(userId, normalizedPrefix);
        result.put("stats", index.statistics(userId, fingerprint).asMap());
        result.put("items", files.stream().limit(limit).toList());
        result.put("prefix", normalizedPrefix);
        result.put("has_more", files.size() > limit);
        result.put("embedding", configured.map(config -> Map.of(
                "configured", configuredWithKey,
                "provider", config.provider(),
                "model", config.model(),
                "fingerprint", fingerprint == null ? "" : fingerprint
        )).orElseGet(() -> Map.of("configured", false, "provider", "", "model", "", "fingerprint", "")));
        return result;
    }

    /** 返回单个文件的索引元数据；不存在时抛出稳定业务异常。 */
    public Map<String, Object> file(UUID userId, String path) {
        requireUser(userId);
        Map<String, Object> result = index.file(userId, normalizePath(path));
        if (result == null) throw new IllegalArgumentException("indexed file not found");
        return result;
    }

    /** 同步抽取并替换一个文本文件的当前 revision 文档。 */
    public Map<String, Object> indexFile(UUID userId, String path) {
        requireUser(userId);
        return indexing.indexFile(userId, normalizePath(path));
    }

    /** 单文件直接抽取；多文件请求由任务模块记录执行状态，但入口仍是索引业务 API。 */
    public Map<String, Object> indexFiles(UUID userId, List<String> paths, boolean force) {
        requireUser(userId);
        List<String> normalized = normalizePaths(paths);
        if (normalized.size() == 1 || monitor == null) {
            List<Map<String, Object>> results = normalized.stream().map(path -> indexFile(userId, path)).toList();
            return Map.of("operation", "index.file", "status", "succeeded", "items", results);
        }
        return enqueueMonitor(userId, "index.embed", "index.file", normalized, force);
    }

    /** 同步读取图片、生成结构化描述并写入视觉文档。 */
    public Map<String, Object> indexVision(UUID userId, String path) {
        return indexVision(userId, List.of(path), false);
    }

    /** 单图片直接执行；多图片由任务模块记录执行状态，但入口仍是索引业务 API。 */
    public Map<String, Object> indexVision(UUID userId, List<String> paths, boolean force) {
        requireUser(userId);
        List<String> normalizedPaths = normalizePaths(paths);
        if (normalizedPaths.size() > 1 && monitor != null) {
            return enqueueMonitor(userId, "index.vision", "index.vision", normalizedPaths, force);
        }
        String normalized = normalizedPaths.get(0);
        Map<String, Object> described = vision.describeFile(userId, normalized);
        Object description = described.get("description");
        if (description == null) throw new IllegalStateException("vision description is empty");
        try {
            Map<String, Object> result = new LinkedHashMap<>(indexing.indexDescription(
                    userId, normalized, objectMapper.writeValueAsString(description)));
            result.put("description", description);
            result.put("model", described.get("model"));
            result.put("embedding", embeddings.embed(userId, List.of(normalized), 64, force));
            return result;
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("vision description encoding failed", error);
        }
    }

    /** 同步向量化指定范围的当前文档块；空 paths 表示 owner 全部文档。 */
    public Map<String, Object> vectorize(UUID userId, List<String> paths, boolean force, int limit) {
        requireUser(userId);
        List<String> normalized = paths == null ? List.of() : normalizePaths(paths);
        if ((normalized.size() != 1 || force) && monitor != null) {
            return enqueueMonitor(userId, "index.embed", "index.vectors", normalized, force);
        }
        return embeddings.embed(userId, normalized, Math.max(1, Math.min(limit, 1000)), force);
    }

    /** 直接清空向量列，保留文件和文本/视觉文档正文。 */
    public Map<String, Object> clearVectors(UUID userId) {
        requireUser(userId);
        return Map.of("cleared_vectors", index.clearEmbeddings(userId), "status", "vectors_cleared");
    }

    /** 删除不再对应当前文件 revision 的失效索引文档。 */
    public Map<String, Object> cleanup(UUID userId) {
        requireUser(userId);
        return Map.of("removed", index.cleanup(userId), "status", "stale_indexes_removed");
    }

    /** 同步重建指定前缀的全文文档；空前缀表示 owner 全部文件。 */
    public Map<String, Object> rebuild(UUID userId, String prefix) {
        requireUser(userId);
        String normalizedPrefix = normalizePrefix(prefix);
        if (monitor == null) return indexing.rebuild(userId, normalizedPrefix);
        return monitor.submit(userId, "index.rebuild", "index.rebuild", List.of(), false, normalizedPrefix)
                .map(submission -> monitorResult("index.rebuild", submission))
                .orElseGet(() -> indexing.rebuild(userId, normalizedPrefix));
    }

    private Map<String, Object> enqueueMonitor(UUID userId, String taskType, String operation,
                                                List<String> paths, boolean force) {
        if (monitor == null) return Map.of("operation", operation, "status", "succeeded");
        return monitor.submit(userId, operation, taskType, paths, force, "")
                .map(submission -> monitorResult(operation, submission))
                .orElseGet(() -> Map.of("operation", operation, "status", "succeeded"));
    }

    private Map<String, Object> monitorResult(String operation, IndexExecutionMonitor.Submission submission) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("operation", operation);
        response.put("accepted", true);
        response.put("created", submission.created());
        response.put("status", submission.status());
        response.put("task", submission.task());
        response.put("monitor", Map.of("task_id", submission.taskId(), "status", submission.status()));
        return response;
    }

    private List<String> normalizePaths(List<String> paths) {
        if (paths == null || paths.isEmpty()) throw new IllegalArgumentException("paths must contain at least one path");
        return paths.stream().map(this::normalizePath).distinct().toList();
    }

    private String normalizePath(String path) {
        String normalized = path == null ? "" : path.trim().replace('\\', '/');
        if (normalized.isBlank() || normalized.length() > 4096
                || normalized.startsWith("/") || normalized.endsWith("/")
                || normalized.contains("//") || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid index path");
        }
        for (String component : normalized.split("/", -1)) {
            if (component.isBlank() || component.equals(".") || component.equals("..")
                    || component.equals(".index") || component.equals(".trash")
                    || component.equals(".storage.lock") || component.startsWith(".upload.")
                    || component.startsWith(".copy.") || component.startsWith(".copy-old.")) {
                throw new IllegalArgumentException("invalid index path");
            }
        }
        return normalized;
    }

    private String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) return "";
        String normalized = prefix.trim().replace('\\', '/');
        if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.isBlank()) return "";
        return normalizePath(normalized);
    }

    private static void requireUser(UUID userId) {
        if (userId == null) throw new IllegalArgumentException("userId must not be null");
    }
}
