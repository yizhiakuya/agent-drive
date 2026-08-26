package com.agentdrive.index;

import com.agentdrive.vision.VisionDescriptionPort;
import com.agentdrive.observability.BusinessMetrics;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 索引业务资源的 owner-scoped 应用服务。
 *
 * <p>这里定义的是正常业务操作：查询索引状态、写入单文件文档、写入视觉描述、
 * 向量化、清空向量和清理失效索引。HTTP Controller 与 Agent operation 共享这条
 * 边界；索引、视觉和向量操作在当前请求内直接执行，不创建任务记录。</p>
 */
@Service
@Profile({"java-files", "java-auth", "java-chat"})
public final class IndexDomainService {
    private final IndexStore index;
    private final IndexingService indexing;
    private final EmbeddingService embeddings;
    private final EmbeddingRuntimeConfig embeddingConfig;
    private final VisionDescriptionPort vision;

    public IndexDomainService(IndexStore index,
                              IndexingService indexing,
                              EmbeddingService embeddings,
                              EmbeddingRuntimeConfig embeddingConfig,
                              VisionDescriptionPort vision) {
        this.index = index;
        this.indexing = indexing;
        this.embeddings = embeddings;
        this.embeddingConfig = embeddingConfig;
        this.vision = vision;
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
        int requestedLimit = Math.max(1, Math.min(limit, 1000));
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> files = index.files(userId, normalizedPrefix, requestedLimit + 1);
        result.put("stats", index.statistics(userId, fingerprint).asMap());
        result.put("items", files.stream().limit(requestedLimit).toList());
        result.put("prefix", normalizedPrefix);
        result.put("has_more", files.size() > requestedLimit);
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

    /** 直接抽取并写入文本索引；多文件请求逐个执行并返回逐项结果。 */
    public Map<String, Object> indexFiles(UUID userId, List<String> paths, boolean force) {
        requireUser(userId);
        long startedAt = System.nanoTime();
        List<String> normalized = normalizePaths(paths);
        try {
            List<Map<String, Object>> results = normalized.stream()
                    .map(path -> executeItem(path, () -> indexFile(userId, path)))
                    .toList();
            Map<String, Object> result = batchResult("index.file", results);
            recordIndexMetric("text", userId, normalized.size(), result, startedAt);
            return result;
        } catch (RuntimeException error) {
            BusinessMetrics.index("text", userId, normalized.size(), 0, normalized.size(), elapsedMillis(startedAt), "");
            throw error;
        }
    }

    /** 同步读取图片、生成综合描述并写入视觉文档。 */
    public Map<String, Object> indexVision(UUID userId, String path) {
        return indexVision(userId, List.of(path), false);
    }

    /** 直接执行图片描述、视觉文档写入和视觉向量生成；多图片逐个执行。 */
    public Map<String, Object> indexVision(UUID userId, List<String> paths, boolean force) {
        requireUser(userId);
        long startedAt = System.nanoTime();
        VisionPathSelection selection = expandVisionPaths(userId, normalizePaths(paths));
        List<String> normalizedPaths = selection.paths();
        try {
            vision.requireReady(userId);
            if (normalizedPaths.isEmpty()) {
                Map<String, Object> result = batchResult("index.vision", List.of());
                result.put("status", selection.skipped().isEmpty() ? "succeeded" : "partial");
                result.put("ok", selection.skipped().isEmpty());
                addSkipped(result, selection.skipped());
                recordIndexMetric("vision", userId, selection.skipped().size(), result, startedAt);
                return result;
            }
            Map<String, Object> described = vision.describeFiles(userId, normalizedPaths);
            Map<String, Map<String, Object>> descriptions = new LinkedHashMap<>();
            Object rawItems = described.get("items");
            if (rawItems instanceof List<?> items) {
                for (Object rawItem : items) {
                    if (rawItem instanceof Map<?, ?> item && item.get("path") != null) {
                        Map<String, Object> value = new LinkedHashMap<>();
                        item.forEach((key, entry) -> value.put(String.valueOf(key), entry));
                        descriptions.put(String.valueOf(item.get("path")), value);
                    }
                }
            }
            List<Map<String, Object>> results = normalizedPaths.stream().map(path -> {
                Map<String, Object> item = descriptions.get(path);
                if (item == null || !(item.get("description") instanceof String description)
                        || description.isBlank()) {
                    Map<String, Object> failed = new LinkedHashMap<>();
                    failed.put("path", path);
                    failed.put("indexed", false);
                    failed.put("status", "error");
                    failed.put("error", item == null
                            ? "vision description missing"
                            : String.valueOf(item.getOrDefault("error", "vision description failed")));
                    return failed;
                }
                return executeItem(path, () -> indexVisionDescription(userId, path, item, force));
            }).toList();
            Map<String, Object> result = batchResult("index.vision", results);
            addSkipped(result, selection.skipped());
            recordIndexMetric("vision", userId, normalizedPaths.size(), result, startedAt);
            return result;
        } catch (RuntimeException error) {
            BusinessMetrics.index("vision", userId, normalizedPaths.size(), 0, normalizedPaths.size(), elapsedMillis(startedAt), "");
            throw error;
        }
    }

    /**
     * 将目录视觉索引请求展开为服务端支持的图片文件，避免 Agent 把目录误传给单图接口。
     * 非图片文件不进入视觉批次；HEIF 等图片扩展名会作为 skipped 返回，不触发重复失败调用。
     */
    private VisionPathSelection expandVisionPaths(UUID userId, List<String> requested) {
        LinkedHashSet<String> supported = new LinkedHashSet<>();
        LinkedHashSet<String> skipped = new LinkedHashSet<>();
        for (String path : requested) {
            List<Map<String, Object>> listed = index.files(userId, path);
            boolean directFile = listed.stream().anyMatch(item -> path.equals(String.valueOf(item.get("path"))));
            if (listed.isEmpty()) {
                // A new physical file may not have an index row yet. Keep the
                // direct path so the vision port can perform the authoritative
                // existence/type check instead of silently skipping it.
                supported.add(path);
                continue;
            }
            if (directFile) {
                if (vision.isImage(path)) supported.add(path);
                else if (looksLikeImage(path)) skipped.add(path);
                continue;
            }
            for (Map<String, Object> item : listed) {
                Object rawPath = item.get("path");
                if (!(rawPath instanceof String childPath) || childPath.isBlank()) continue;
                if (vision.isImage(childPath)) supported.add(childPath);
                else if (looksLikeImage(childPath)) skipped.add(childPath);
            }
        }
        return new VisionPathSelection(List.copyOf(supported), List.copyOf(skipped));
    }

    private boolean looksLikeImage(String path) {
        String lower = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".heic") || lower.endsWith(".heif") || lower.endsWith(".avif")
                || lower.endsWith(".tif") || lower.endsWith(".tiff");
    }

    private void addSkipped(Map<String, Object> result, List<String> skipped) {
        result.put("skipped", skipped.size());
        if (!skipped.isEmpty()) {
            result.put("ok", false);
            result.put("status", "partial");
            result.put("skipped_paths", skipped.stream().limit(64).toList());
        }
    }

    private record VisionPathSelection(List<String> paths, List<String> skipped) {
    }

    /** 同步向量化指定范围的当前文档块；空 paths 表示 owner 全部文档。 */
    public Map<String, Object> vectorize(UUID userId, List<String> paths, boolean force, int limit) {
        requireUser(userId);
        long startedAt = System.nanoTime();
        // 空路径是全量向量化的合法语义；只有显式的非空列表才需要逐项路径校验。
        List<String> normalized = paths == null || paths.isEmpty() ? List.of() : normalizePaths(paths);
        try {
            Map<String, Object> result = embeddings.embed(userId, normalized, Math.max(1, Math.min(limit, 1000)), force);
            int requested = number(result.get("total"), normalized.isEmpty() ? number(result.get("embedded"), 0) : normalized.size());
            recordIndexMetric("vector", userId, requested, result, startedAt);
            return result;
        } catch (RuntimeException error) {
            BusinessMetrics.index("vector", userId, normalized.size(), 0, normalized.size(), elapsedMillis(startedAt), "");
            throw error;
        }
    }

    /** 执行一个批量项并把领域异常转换成可展示的逐项错误。 */
    private Map<String, Object> executeItem(String path, java.util.function.Supplier<Map<String, Object>> operation) {
        try {
            return operation.get();
        } catch (RuntimeException error) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", path);
            result.put("indexed", false);
            result.put("status", "error");
            result.put("error", safeMessage(error));
            return result;
        }
    }

    /** 统一计算批量索引的 succeeded/partial/failed 状态，避免失败项被伪报为成功。 */
    private Map<String, Object> batchResult(String operation, List<Map<String, Object>> items) {
        int failed = (int) items.stream().filter(item -> !itemSucceeded(item)).count();
        String status = failed == 0 ? "succeeded" : failed == items.size() ? "failed" : "partial";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", failed == 0);
        result.put("operation", operation);
        result.put("status", status);
        result.put("items", items);
        result.put("failed", failed);
        return result;
    }

    private boolean itemSucceeded(Map<String, Object> item) {
        if (Boolean.FALSE.equals(item.get("indexed"))) return false;
        Object embedding = item.get("embedding");
        if (embedding instanceof Map<?, ?> map && Boolean.FALSE.equals(map.get("vectorized"))) return false;
        return !Boolean.FALSE.equals(item.get("vectorized"));
    }

    private String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.length() <= 500 ? message : message.substring(0, 497) + "...";
    }

    /** 记录索引结果，保持日志字段稳定且不写入文件路径或正文。 */
    private void recordIndexMetric(String operation, UUID userId, int requested,
                                   Map<String, Object> result, long startedAt) {
        int failed = number(result.get("failed"), 0);
        int succeeded = number(result.get("embedded"), Math.max(0, requested - failed));
        Object rawItems = result.get("items");
        if (rawItems instanceof List<?> items) succeeded = Math.max(0, items.size() - failed);
        if (Boolean.FALSE.equals(result.get("vectorized"))) {
            failed = Math.max(failed, requested > 0 ? requested : 1);
            succeeded = 0;
        }
        BusinessMetrics.index(operation, userId, requested, succeeded, failed,
                elapsedMillis(startedAt), stringValue(result.get("provider")));
    }

    private int number(Object value, int fallback) {
        return value instanceof Number number ? Math.max(0, number.intValue()) : fallback;
    }

    private String stringValue(Object value) {
        return value instanceof String text ? text : "";
    }

    private long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
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
        return indexing.rebuild(userId, normalizedPrefix);
    }

    private Map<String, Object> indexVisionDescription(UUID userId, String path,
                                                       Map<String, Object> described,
                                                       boolean force) {
        Object description = described.get("description");
        if (!(description instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("vision description is empty");
        }
        Map<String, Object> result = new LinkedHashMap<>(indexing.indexDescription(userId, path, text));
        result.put("description", description);
        result.put("model", described.get("model"));
        result.put("embedding", embeddings.embed(userId, List.of(path), 64, force));
        return result;
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
