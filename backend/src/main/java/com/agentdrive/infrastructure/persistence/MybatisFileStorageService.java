package com.agentdrive.infrastructure.persistence;

import com.agentdrive.files.FileStorageException;
import com.agentdrive.files.FileStorageService;
import com.agentdrive.index.EmbeddingFingerprint;
import com.agentdrive.index.EmbeddingRuntimeConfig;
import com.agentdrive.index.SemanticSearchService;
import com.agentdrive.outbox.OutboxStore;
import com.agentdrive.tasks.TaskStore;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.agentdrive.infrastructure.persistence.mapper.FileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * 持久化 owner 文件树的磁盘内容、文件 metadata、回收站和上传去重索引。
 * <p>每个 owner 的可见根目录是 {@code root/&lt;owner UUID&gt;}。公共路径先规范化为
 * owner 根下的相对路径，再拒绝绝对路径、{@code ..} 越界、首段内部名称、符号链接和非普通
 * 文件类型；因此调用方不能通过文件 API 访问其他 owner 或 {@code .trash} 等内部区域。</p>
 * <p>上传、文本写入、移动、复制和回收站变更先在 storage lock 内完成磁盘可见性提交，
 * 再更新 MyBatis metadata、revision、dedupe 行，并在同一数据库事务写入
 * {@code file.changed} outbox。storage lock 会持有到事务完成：提交后清理旧目标，回滚或
 * 提交失败时恢复原文件，避免客户端收到失败时磁盘内容却已经不可逆改变。</p>
 */
public class MybatisFileStorageService implements FileStorageService {
    private static final int INDEX_DETAIL_LIMIT = 100;
    private static final int FILE_LIST_LIMIT = 1000;
    private static final int MAX_VERSION_SNAPSHOTS = 20;
    private static final Set<String> INTERNAL_NAMES = Set.of(".index", ".trash", ".versions", ".storage.lock");
    private static final String COPY_PREFIX = ".copy.";
    private static final String COPY_OLD_PREFIX = ".copy-old.";
    private static final String COPY_MARKER_SUFFIX = ".txn.json";
    private static final Logger LOGGER = LoggerFactory.getLogger(MybatisFileStorageService.class);
    private static final Set<String> TEXT_SUFFIXES = Set.of(
            ".txt", ".md", ".py", ".js", ".ts", ".jsx", ".tsx", ".json", ".yaml", ".yml",
            ".toml", ".csv", ".html", ".css", ".xml", ".log", ".sh", ".ini", ".conf"
    );
    private static final Map<String, String> MEDIA_TYPES = Map.ofEntries(
            Map.entry(".pdf", "application/pdf"), Map.entry(".png", "image/png"),
            Map.entry(".jpg", "image/jpeg"), Map.entry(".jpeg", "image/jpeg"),
            Map.entry(".gif", "image/gif"), Map.entry(".webp", "image/webp"),
            Map.entry(".bmp", "image/bmp"), Map.entry(".mp4", "video/mp4"),
            Map.entry(".webm", "video/webm"), Map.entry(".ogg", "video/ogg"),
            Map.entry(".mov", "video/quicktime"), Map.entry(".m4v", "video/mp4"),
            Map.entry(".mp3", "audio/mpeg"), Map.entry(".wav", "audio/wav"),
            Map.entry(".m4a", "audio/mp4"), Map.entry(".flac", "audio/flac")
    );

    private final FileMapper mapper;
    private final Path root;
    private final long maxUploadBytes;
    private final OutboxStore outbox;
    private final EmbeddingRuntimeConfig embeddingConfigs;
    private final SemanticSearchService semanticSearch;
    private final TaskStore tasks;
    private final ReentrantLock mutationLock = new ReentrantLock();

    /**
     * 创建不发送文件变更 outbox 事件的服务。
     * <p>构造过程仍会把根路径转为绝对规范路径并确保根目录存在；上传、移动等成功后的
     * metadata/dedupe 更新不会通过该实例发布 {@code file.changed} 事件。</p>
     * @param mapper 读写文件 metadata、revision、trash 和 dedupe 的 Mapper。
     * @param root 所有 owner 目录的父路径。
     * @param maxUploadBytes 单个上传临时文件允许发布的最大字节数。
     * @throws FileStorageException 无法创建根目录时抛出。
     */
    public MybatisFileStorageService(FileMapper mapper, Path root, long maxUploadBytes) {
        this(mapper, root, maxUploadBytes, null);
    }

    /**
     * 创建带文件变更 outbox 端口的服务。
     * <p>根路径会在构造时创建；{@code outbox} 非空时，文件内容和 metadata 变更完成后由
     * 本服务写入 {@code file.changed} 事件，供异步索引或同步流程消费。</p>
     * @param mapper 读写文件 metadata、revision、trash 和 dedupe 的 Mapper。
     * @param root 所有 owner 目录的父路径。
     * @param maxUploadBytes 单个上传临时文件允许发布的最大字节数。
     * @param outbox 内容变化后写入索引/同步事件的 outbox；为空时不发布事件。
     * @throws FileStorageException 无法创建根目录时抛出。
     */
    public MybatisFileStorageService(FileMapper mapper, Path root, long maxUploadBytes, OutboxStore outbox) {
        this(mapper, root, maxUploadBytes, outbox, null);
    }

    /**
     * 创建带索引状态查询的文件服务。
     *
     * <p>embedding 配置是可选依赖，以便纯文件测试和回滚工具仍可复用本服务；生产装配会
     * 注入它来区分当前模型的有效向量与旧模型遗留向量。</p>
     *
     * @param mapper 读写文件 metadata、revision、trash 和 dedupe 的 Mapper。
     * @param root 所有 owner 目录的父路径。
     * @param maxUploadBytes 单个上传临时文件允许发布的最大字节数。
     * @param outbox 内容变化后写入索引/同步事件的 outbox。
     * @param embeddingConfigs 读取当前 owner embedding 配置的运行时端口，可为空。
     */
    public MybatisFileStorageService(FileMapper mapper, Path root, long maxUploadBytes,
                                     OutboxStore outbox, EmbeddingRuntimeConfig embeddingConfigs) {
        this(mapper, root, maxUploadBytes, outbox, embeddingConfigs, null);
    }

    /**
     * 创建带语义搜索端口的文件服务。
     *
     * @param mapper 读写文件 metadata、revision、trash 和 dedupe 的 Mapper。
     * @param root 所有 owner 目录的父路径。
     * @param maxUploadBytes 单个上传临时文件允许发布的最大字节数。
     * @param outbox 内容变化后写入索引/同步事件的 outbox。
     * @param embeddingConfigs 读取当前 owner embedding 配置的运行时端口。
     * @param semanticSearch owner-scoped pgvector 语义搜索服务。
     */
    public MybatisFileStorageService(FileMapper mapper, Path root, long maxUploadBytes,
                                     OutboxStore outbox, EmbeddingRuntimeConfig embeddingConfigs,
                                     SemanticSearchService semanticSearch) {
        this(mapper, root, maxUploadBytes, outbox, embeddingConfigs, semanticSearch, null);
    }

    /**
     * 创建带任务总览缓存失效通知的文件服务。
     * @param mapper 读写文件 metadata、revision、trash 和 dedupe 的 Mapper。
     * @param root 所有 owner 目录的父路径。
     * @param maxUploadBytes 单个上传临时文件允许发布的最大字节数。
     * @param outbox 内容变化后写入索引/同步事件的 outbox。
     * @param embeddingConfigs 读取当前 owner embedding 配置的运行时端口。
     * @param semanticSearch owner-scoped pgvector 语义搜索服务。
     * @param tasks 文件变更后需要失效索引概览缓存的任务存储，可为空。
     */
    public MybatisFileStorageService(FileMapper mapper, Path root, long maxUploadBytes,
                                     OutboxStore outbox, EmbeddingRuntimeConfig embeddingConfigs,
                                     SemanticSearchService semanticSearch, TaskStore tasks) {
        this.mapper = mapper;
        this.root = root.toAbsolutePath().normalize();
        this.maxUploadBytes = maxUploadBytes;
        this.outbox = outbox;
        this.embeddingConfigs = embeddingConfigs;
        this.semanticSearch = semanticSearch;
        this.tasks = tasks;
        try {
            Files.createDirectories(this.root);
        } catch (IOException error) {
            throw new FileStorageException(500, "无法初始化文件存储", error);
        }
        try (StorageLock ignored = storageLock()) {
            recoverPendingCopyTransactions();
        } catch (IOException error) {
            throw new FileStorageException(500, "恢复复制事务失败", error);
        }
    }

    /**
     * 列出 owner 根目录或指定目录的直接子项，并附带所在文件系统的磁盘用量。
     * <p>结果按目录优先、名称大小写不敏感排序；直接子项中的内部名称被隐藏。列表只保留
     * 最终返回上限以内的候选路径，并把缺失或已变化的 metadata 以一次批量写入同步到数据库。</p>
     * @param ownerId 文件所属 owner 的 UUID。
     * @param path owner 根下的相对目录；空值表示根目录。
     * @return 包含规范化 {@code path}、{@code items} 和 {@code disk} 的文件列表结构。
     * @throws FileStorageException owner 未认证、路径越界/含符号链接、目标不是目录或读取失败时抛出。
     */
    @Override
    public Map<String, Object> list(UUID ownerId, String path) {
        return list(ownerId, path, "");
    }

    /**
     * 按名称/路径或语义模式列出文件。
     * 语义模式先复用同一套 owner 路径和目录检查，再调用索引服务，确保搜索结果不能越过
     * 文件 API 的安全边界；磁盘信息仍由文件服务统一补充。
     *
     * @param ownerId 文件归属用户的 UUID。
     * @param path 搜索根目录，空值表示 owner 根目录。
     * @param query 名称/路径关键词或自然语言搜索问题。
     * @param mode {@code name} 或 {@code semantic}。
     * @return 文件列表及搜索结果元数据。
     */
    @Override
    public Map<String, Object> list(UUID ownerId, String path, String query, String mode) {
        return list(ownerId, path, query, mode, FILE_LIST_LIMIT, null);
    }

    /**
     * 分页/最低相关度版文件列表；返回多取一条后的 {@code has_more}，避免客户端以
     * “返回条数等于 limit”猜测是否还有结果。
     */
    @Override
    public Map<String, Object> list(UUID ownerId, String path, String query, String mode,
                                    int limit, Double minScore) {
        return list(ownerId, path, query, mode, limit, minScore, "all", null, null);
    }

    @Override
    public Map<String, Object> list(UUID ownerId, String path, String query, String mode,
                                    int limit, Double minScore, String type,
                                    Double modifiedAfter, Double modifiedBefore) {
        String normalizedMode = mode == null ? "name" : mode.trim().toLowerCase(Locale.ROOT);
        int requestedLimit = Math.max(1, Math.min(limit, FILE_LIST_LIMIT));
        String normalizedType = normalizeTypeFilter(type);
        if ("name".equals(normalizedMode)) {
            return listByName(ownerId, path, query, requestedLimit, normalizedType, modifiedAfter, modifiedBefore);
        }
        if (!"semantic".equals(normalizedMode)) {
            throw new FileStorageException(400, "不支持的文件搜索模式");
        }
        Path directory = safePath(ownerId, path, false);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileStorageException(404, "目录不存在");
        }
        if (semanticSearch == null) {
            throw new FileStorageException(503, "语义搜索服务未启用");
        }
        try {
            Map<String, Object> result = new LinkedHashMap<>(semanticSearch.search(
                    ownerId, normalizePath(path), query, Math.min(100, requestedLimit), minScore,
                    normalizedType, modifiedAfter, modifiedBefore));
            decorateFavoriteFlags(ownerId, result);
            result.put("disk", diskUsage());
            return result;
        } catch (IOException error) {
            throw new FileStorageException(500, "读取磁盘用量失败", error);
        }
    }

    /** 返回当前 owner 最近收藏的仍然存在且可见的文件/目录。 */
    @Override
    public Map<String, Object> listFavorites(UUID ownerId, int limit) {
        int requestedLimit = Math.max(1, Math.min(limit, 100));
        List<Map<String, Object>> rows = mapper.selectFavorites(ownerId.toString(), requestedLimit + 20);
        return listTrackedPaths(ownerId, rows, requestedLimit, "favorites");
    }

    /** 返回当前 owner 最近访问的仍然存在且可见的普通文件。 */
    @Override
    public Map<String, Object> listRecent(UUID ownerId, int limit) {
        int requestedLimit = Math.max(1, Math.min(limit, 100));
        List<Map<String, Object>> rows = mapper.selectRecent(ownerId.toString(), requestedLimit + 20);
        return listTrackedPaths(ownerId, rows, requestedLimit, "recent");
    }

    /** 列出指定文件的真实内容快照，数据库或磁盘孤儿只跳过不展示。 */
    @Override
    public Map<String, Object> listVersions(UUID ownerId, String path, int limit) {
        String normalized = normalizePath(path);
        requireOwnedFile(ownerId, normalized);
        int requestedLimit = Math.max(1, Math.min(limit, 50));
        List<Map<String, Object>> rows = mapper.selectVersionSnapshots(ownerId.toString(), normalized,
                requestedLimit + 1);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            try {
                Path snapshot = safeInternalVersion(ownerId, String.valueOf(row.get("snapshot_path")));
                if (!Files.isRegularFile(snapshot, LinkOption.NOFOLLOW_LINKS)) continue;
                items.add(mapOf("version_id", row.get("version_id"),
                        "source_revision", row.get("source_revision"),
                        "size", row.get("size_bytes"),
                        "content_md5", row.get("content_md5"),
                        "content_sha256", row.get("content_sha256"),
                        "created_at", row.get("created_at")));
                if (items.size() >= requestedLimit) break;
            } catch (FileStorageException ignored) {
                // Malformed internal rows are treated as orphans, never as public paths.
            }
        }
        Map<String, Object> result = mapOf("path", normalized, "items", items,
                "has_more", rows.size() > requestedLimit);
        Map<String, Object> current = mapper.selectByPath(ownerId.toString(), normalized);
        if (current != null) result.put("current_revision", current.get("revision"));
        return result;
    }

    /** 将快照内容作为新 revision 原子恢复到文件路径。 */
    @Override
    @Transactional
    public Map<String, Object> restoreVersion(UUID ownerId, String path, String versionId) {
        String normalized = normalizePath(path);
        if (versionId == null || versionId.isBlank()) {
            throw new FileStorageException(400, "version_id 不能为空");
        }
        requireOwnedFile(ownerId, normalized);
        Map<String, Object> row;
        try {
            UUID.fromString(versionId);
            row = mapper.selectVersionSnapshot(ownerId.toString(), normalized, versionId);
        } catch (IllegalArgumentException error) {
            throw new FileStorageException(400, "version_id 无效");
        }
        if (row == null) throw new FileStorageException(404, "文件版本不存在");
        Path snapshot = safeInternalVersion(ownerId, String.valueOf(row.get("snapshot_path")));
        if (!Files.isRegularFile(snapshot, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileStorageException(404, "文件版本内容不存在");
        }
        Path temp = createUploadTemp();
        try {
            Files.copy(snapshot, temp, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            int separator = normalized.lastIndexOf('/');
            String directory = separator < 0 ? "" : normalized.substring(0, separator);
            String filename = separator < 0 ? normalized : normalized.substring(separator + 1);
            Map<String, Object> published = publishUpload(ownerId, directory, filename, temp, "", false);
            return mapOf("restored", published.get("uploaded"), "version_id", versionId);
        } catch (IOException error) {
            throw new FileStorageException(500, "恢复文件版本失败", error);
        } finally {
            discardTemp(temp);
        }
    }

    /** 添加或移除收藏，并在写入前重新执行 owner 路径和物理类型检查。 */
    @Override
    @Transactional
    public Map<String, Object> setFavorite(UUID ownerId, String path, boolean favorite) {
        String normalized = normalizePath(path);
        if (normalized.isBlank()) throw new FileStorageException(400, "收藏路径不能为空");
        Path target = safePath(ownerId, normalized, false);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileStorageException(404, "文件或目录不存在");
        }
        rejectSpecial(target);
        if (favorite) mapper.addFavorite(ownerId.toString(), normalized);
        else mapper.removeFavorite(ownerId.toString(), normalized);
        return mapOf("path", normalized, "favorite", favorite);
    }

    /** 记录一次用户可见的普通文件访问；数据库异常只记日志，不影响读取主流程。 */
    @Override
    public void touchAccess(UUID ownerId, String path) {
        if (ownerId == null || path == null || path.isBlank()) return;
        String normalized = normalizePath(path);
        try {
            Path target = safePath(ownerId, normalized, false);
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                mapper.touchAccess(ownerId.toString(), normalized);
            }
        } catch (RuntimeException ignored) {
            LOGGER.warn("文件访问记录写入失败 owner_id={} path={}", ownerId, normalized);
        }
    }

    /** 把收藏/访问表中的路径重新解析为当前可见条目，过滤删除、越界和内部路径。 */
    private Map<String, Object> listTrackedPaths(UUID ownerId, List<Map<String, Object>> rows,
                                                  int requestedLimit, String mode) {
        List<Map<String, Object>> visible = new ArrayList<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                if (row == null || row.get("path") == null) continue;
                String trackedPath = normalizePath(String.valueOf(row.get("path")));
                if (trackedPath.isBlank()) continue;
                try {
                    Path target = safePath(ownerId, trackedPath, false);
                    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
                    rejectSpecial(target);
                    ListedEntry entry = listedEntry(ownerId, target);
                    Map<String, Object> item = mapOf(
                            "name", target.getFileName().toString(),
                            "path", trackedPath,
                            "is_dir", entry.directory(),
                            "size", entry.size(),
                            "mtime", entry.modifiedAt(),
                            "favorite", "favorites".equals(mode));
                    if (!entry.directory()) {
                        item.put("index", indexStatus(ownerId, trackedPath, currentEmbeddingFingerprint(ownerId)));
                    }
                    if ("recent".equals(mode)) {
                        item.put("last_accessed", row.get("last_accessed_at"));
                        item.put("access_count", row.get("access_count"));
                        item.put("favorite", isFavorite(ownerId, trackedPath));
                    }
                    visible.add(item);
                } catch (RuntimeException | IOException ignored) {
                    // Stale tracking rows are intentionally invisible; cleanup can happen lazily later.
                }
            }
        }
        boolean hasMore = visible.size() > requestedLimit;
        if (hasMore) visible = new ArrayList<>(visible.subList(0, requestedLimit));
        try {
            return mapOf("path", "", "query", "", "mode", mode,
                    "items", visible, "limit", requestedLimit, "has_more", hasMore, "disk", diskUsage());
        } catch (IOException error) {
            throw new FileStorageException(500, "读取磁盘用量失败", error);
        }
    }

    private boolean isFavorite(UUID ownerId, String path) {
        List<Map<String, Object>> rows = mapper.selectFavoritePaths(ownerId.toString(), List.of(path));
        return rows != null && !rows.isEmpty();
    }

    private void decorateFavoriteFlags(UUID ownerId, Map<String, Object> result) {
        Object rawItems = result.get("items");
        if (!(rawItems instanceof List<?> items) || items.isEmpty()) return;
        List<String> paths = new ArrayList<>();
        for (Object raw : items) {
            if (raw instanceof Map<?, ?> item && item.get("path") != null) paths.add(String.valueOf(item.get("path")));
        }
        if (paths.isEmpty()) return;
        Set<String> favorites = new HashSet<>();
        List<Map<String, Object>> rows = mapper.selectFavoritePaths(ownerId.toString(), paths);
        if (rows != null) for (Map<String, Object> row : rows) {
            if (row != null && row.get("path") != null) favorites.add(String.valueOf(row.get("path")));
        }
        for (Object raw : items) {
            if (raw instanceof Map<?, ?> item && item.get("path") != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mutable = (Map<String, Object>) item;
                mutable.put("favorite", favorites.contains(String.valueOf(item.get("path"))));
            }
        }
    }

    /**
     * 列出目录，或在目录子树内按名称/相对路径搜索可见条目。
     *
     * <p>搜索仍在文件服务内部执行，返回结构与普通列表兼容；最多返回 1000 项，避免一次
     * 查询把整个 owner 文件树放入 API 响应。</p>
     *
     * @param ownerId 文件所属 owner 的 UUID。
     * @param path owner 根下的相对目录；空值表示根目录。
     * @param query 可选名称或路径包含查询词。
     * @return 包含规范化路径、条目、搜索词和磁盘用量的文件列表结构。
     */
    @Override
    public Map<String, Object> list(UUID ownerId, String path, String query) {
        return listByName(ownerId, path, query, FILE_LIST_LIMIT, "all", null, null);
    }

    private Map<String, Object> listByName(UUID ownerId, String path, String query, int requestedLimit,
                                           String type, Double modifiedAfter, Double modifiedBefore) {
        Path directory = safePath(ownerId, path, false);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileStorageException(404, "目录不存在");
        }
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String fingerprint = currentEmbeddingFingerprint(ownerId);
        try (Stream<Path> children = normalizedQuery.isBlank() ? Files.list(directory) : Files.walk(directory)) {
            int probeLimit = Math.min(FILE_LIST_LIMIT + 1, Math.max(1, requestedLimit + 1));
            List<Path> sorted = retainListLimit(ownerId, directory, normalizedQuery, children, probeLimit,
                    type, modifiedAfter, modifiedBefore);
            boolean hasMore = sorted.size() > requestedLimit;
            if (hasMore) sorted = sorted.subList(0, requestedLimit);
            List<ListedEntry> entries = new ArrayList<>(sorted.size());
            for (Path child : sorted) {
                entries.add(listedEntry(ownerId, child));
            }
            synchronizeListedMetadata(ownerId, entries);

            List<Map<String, Object>> items = new ArrayList<>(entries.size());
            Set<String> favoritePaths = favoritePathSet(ownerId,
                    entries.stream().map(ListedEntry::relativePath).toList());
            for (ListedEntry entry : entries) {
                Path child = entry.path();
                rejectSpecial(child);
                Map<String, Object> item = mapOf(
                        "name", child.getFileName().toString(),
                        "path", entry.relativePath(),
                        "is_dir", entry.directory(),
                        "size", entry.size(),
                        "mtime", entry.modifiedAt(),
                        "favorite", favoritePaths.contains(entry.relativePath())
                );
                if (!entry.directory()) item.put("index", indexStatus(ownerId, entry.relativePath(), fingerprint));
                items.add(item);
            }
            return mapOf("path", normalizePath(path), "query", query == null ? "" : query,
                    "type", type, "modified_after", modifiedAfter, "modified_before", modifiedBefore,
                    "items", items, "limit", requestedLimit, "has_more", hasMore, "disk", diskUsage());
        } catch (IOException error) {
            throw new FileStorageException(500, "读取目录失败", error);
        }
    }

    private Set<String> favoritePathSet(UUID ownerId, List<String> paths) {
        if (paths == null || paths.isEmpty()) return Set.of();
        Set<String> favorites = new HashSet<>();
        List<Map<String, Object>> rows = mapper.selectFavoritePaths(ownerId.toString(), paths);
        if (rows != null) for (Map<String, Object> row : rows) {
            if (row != null && row.get("path") != null) favorites.add(String.valueOf(row.get("path")));
        }
        return favorites;
    }

    /**
     * 从目录流中保留排序最靠前的固定数量路径，避免 {@link Stream#sorted()} 为整个文件树
     * 建立无界中间集合。遍历仍覆盖搜索目录树，但内存占用固定在列表上限内。
     */
    private List<Path> retainListLimit(UUID ownerId, Path directory, String normalizedQuery,
                                       Stream<Path> children, int limit, String type,
                                       Double modifiedAfter, Double modifiedBefore) {
        Comparator<Path> order = Comparator
                .comparing((Path child) -> !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS))
                .thenComparing(child -> child.getFileName().toString().toLowerCase(Locale.ROOT))
                .thenComparing(child -> relative(ownerId, child).toLowerCase(Locale.ROOT));
        PriorityQueue<Path> selected = new PriorityQueue<>(limit, order.reversed());
        children
                .filter(child -> !child.equals(directory))
                .filter(child -> !isInternal(child.getFileName().toString()))
                .filter(child -> !hasInternalComponent(relative(ownerId, child)))
                .filter(child -> normalizedQuery.isBlank()
                        || relative(ownerId, child).toLowerCase(Locale.ROOT).contains(normalizedQuery))
                .filter(child -> matchesFileFilter(child, type, modifiedAfter, modifiedBefore))
                .forEach(child -> retainPath(selected, child, order, limit));
        return selected.stream().sorted(order).toList();
    }

    private String normalizeTypeFilter(String type) {
        String normalized = type == null || type.isBlank() ? "all" : type.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("all", "file", "folder", "image", "video", "audio", "pdf", "text").contains(normalized)) {
            throw new FileStorageException(400, "不支持的文件类型筛选");
        }
        return normalized;
    }

    private boolean matchesFileFilter(Path candidate, String type,
                                      Double modifiedAfter, Double modifiedBefore) {
        try {
            boolean directory = Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS);
            if ("folder".equals(type) && !directory) return false;
            if ("file".equals(type) && directory) return false;
            if (directory && !Set.of("all", "file", "folder").contains(type)) return false;
            if (!directory && !"all".equals(type) && !"file".equals(type)) {
                String kind = previewKind(suffix(candidate));
                if (!type.equals(kind)) return false;
            }
            double modified = Files.getLastModifiedTime(candidate, LinkOption.NOFOLLOW_LINKS).toMillis() / 1000.0;
            return (modifiedAfter == null || modified >= modifiedAfter)
                    && (modifiedBefore == null || modified <= modifiedBefore);
        } catch (IOException error) {
            return false;
        }
    }

    /** 将一条候选路径放入固定大小的 top-k 集合。 */
    private static void retainPath(PriorityQueue<Path> selected, Path candidate, Comparator<Path> order,
                                   int limit) {
        if (selected.size() < limit) {
            selected.offer(candidate);
        } else if (order.compare(candidate, selected.peek()) < 0) {
            selected.poll();
            selected.offer(candidate);
        }
    }

    /** 读取一个已选路径的文件系统状态，供响应和 metadata 同步共同使用。 */
    private ListedEntry listedEntry(UUID ownerId, Path path) throws IOException {
        boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        long size = directory ? 0 : Files.size(path);
        double modifiedAt = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis() / 1000.0;
        return new ListedEntry(path, relative(ownerId, path), directory, size, modifiedAt);
    }

    /** 只同步缺失或物理状态已变化的 metadata，并把列表请求中的写入合并为一条 SQL。 */
    private void synchronizeListedMetadata(UUID ownerId, List<ListedEntry> entries) {
        if (entries.isEmpty()) return;
        List<String> paths = entries.stream().map(ListedEntry::relativePath).toList();
        List<Map<String, Object>> existing = mapper.selectByPaths(ownerId.toString(), paths);
        Map<String, Map<String, Object>> byPath = new java.util.HashMap<>();
        if (existing != null) {
            for (Map<String, Object> metadata : existing) {
                if (metadata != null && metadata.get("path") != null) {
                    byPath.put(String.valueOf(metadata.get("path")), metadata);
                }
            }
        }
        List<Map<String, Object>> changed = new ArrayList<>();
        for (ListedEntry entry : entries) {
            Map<String, Object> metadata = byPath.get(entry.relativePath());
            if (!metadataMatches(metadata, entry)) {
                changed.add(mapOf("path", entry.relativePath(), "isDir", entry.directory(), "size", entry.size()));
            }
        }
        if (!changed.isEmpty()) {
            mapper.upsertMetadataBatch(ownerId.toString(), changed);
            invalidateOverview(ownerId);
        }
    }

    /** 判断数据库中已有 metadata 是否仍与物理文件状态一致。 */
    private boolean metadataMatches(Map<String, Object> metadata, ListedEntry entry) {
        if (metadata == null) return false;
        boolean directory = metadata.get("is_dir") instanceof Boolean value
                ? value
                : Boolean.parseBoolean(String.valueOf(metadata.get("is_dir")));
        try {
            return directory == entry.directory() && longValue(metadata.get("size_bytes")) == entry.size();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * 返回 owner 文件的大小、修改时间、预览类型和受限文本片段。
     * <p>该入口只接受普通文件，不读取目录或符号链接，也不从 metadata 表回填 revision；
     * 文本预览最多读取 4000 个字节，非文本文件的 {@code snippet} 为 {@code null}。</p>
     * @param ownerId 文件所属 owner 的 UUID。
     * @param path owner 根下的相对路径。
     * @return 包含 {@code path}、{@code name}、{@code size}、{@code modified}、{@code preview_kind}
     *         和 {@code snippet} 的文件信息 Map。
     * @throws FileStorageException owner 路径无效、文件或 metadata 不存在、目标不是普通文件或读取失败时抛出。
     */
    @Override
    public Map<String, Object> info(UUID ownerId, String path) {
        Path file = requireOwnedFile(ownerId, path);
        try {
            String normalizedPath = normalizePath(path);
            String embeddingFingerprint = currentEmbeddingFingerprint(ownerId);
            String suffix = suffix(file);
            String previewKind = previewKind(suffix);
            String snippet = null;
            if ("text".equals(previewKind)) {
                snippet = readTextPreview(file, 4000);
            }
            Map<String, Object> metadata = mapper.selectByPath(ownerId.toString(), normalizedPath);
            Map<String, Object> indexed = indexStatus(ownerId, normalizedPath, embeddingFingerprint);
            indexed.put("detail", indexDetails(ownerId, normalizedPath, embeddingFingerprint));
            touchAccess(ownerId, normalizedPath);
            return mapOf(
                    "path", normalizedPath,
                    "name", file.getFileName().toString(),
                    "size", Files.size(file),
                    "modified", Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis() / 1000.0,
                    "preview_kind", previewKind,
                    "content_type", MEDIA_TYPES.getOrDefault(suffix, "text".equals(previewKind)
                            ? "text/plain" : "application/octet-stream"),
                    "snippet", snippet,
                    "revision", metadata == null ? null : metadata.get("revision"),
                    "indexed", indexed
            );
        } catch (IOException error) {
            throw new FileStorageException(500, "读取文件信息失败", error);
        }
    }

    /**
     * 读取文本文件的完整内容，最多返回 2 MiB，并显式标记截断。
     *
     * @param ownerId 文件所属 owner 的 UUID。
     * @param path owner 根下的文本文件路径。
     * @param maxBytes 调用方请求的最大字节数。
     * @return 文本内容、原始大小、UTF-8 编码和截断标志。
     * @throws FileStorageException 目标不是文本文件、大小参数非法或内容不是有效 UTF-8 时抛出。
     */
    @Override
    public Map<String, Object> content(UUID ownerId, String path, int maxBytes) {
        Path file = requireOwnedFile(ownerId, path);
        int limit = Math.max(1, Math.min(maxBytes, 2 * 1024 * 1024));
        String normalized = normalizePath(path);
        if (!"text".equals(previewKind(suffix(file)))) {
            throw new FileStorageException(415, "只有文本文件支持查看内容");
        }
        try {
            long size = Files.size(file);
            byte[] bytes = new byte[(int) Math.min(size, limit + 1L)];
            int offset = 0;
            try (InputStream input = Files.newInputStream(file)) {
                while (offset < bytes.length) {
                    int read = input.read(bytes, offset, bytes.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
            }
            boolean truncated = size > limit || offset > limit;
            String text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes, 0, Math.min(offset, limit)))
                    .toString();
            touchAccess(ownerId, normalized);
            return mapOf("path", normalized, "name", file.getFileName().toString(),
                    "content", text, "encoding", "UTF-8", "size", size, "truncated", truncated);
        } catch (java.nio.charset.CharacterCodingException error) {
            throw new FileStorageException(415, "文件不是有效的 UTF-8 文本");
        } catch (IOException error) {
            throw new FileStorageException(500, "读取文件内容失败", error);
        }
    }

    /**
     * 按 owner 和 MD5 查找可复用的已上传文件。
     * <p>命中只有在 metadata 存在、物理路径仍是普通文件且记录的 {@code revision} 与 dedupe
     * 行中的 {@code file_revision} 相等时才有效。失效命中会删除该路径的 dedupe 行后返回
     * 404，使索引在读取时自愈；该查询不重新计算文件内容的 MD5。</p>
     * @param ownerId 文件所属 owner 的 UUID。
     * @param md5 32 位十六进制 MD5。
     * @return 包含已上传路径、大小和 {@code deduped=true} 的结果。
     * @throws FileStorageException MD5 格式错误、没有命中或 owner 路径不再安全时抛出。
     */
    @Override
    public Map<String, Object> dedupe(UUID ownerId, String md5) {
        String normalized = validateMd5(md5, true);
        Map<String, Object> hit = mapper.selectDedupe(ownerId.toString(), normalized);
        if (hit == null) {
            throw new FileStorageException(404, "未命中");
        }
        String path = String.valueOf(hit.get("path"));
        Map<String, Object> metadata = mapper.selectByPath(ownerId.toString(), path);
        if (metadata == null || !Files.isRegularFile(safePath(ownerId, path, false), LinkOption.NOFOLLOW_LINKS)
                || !sameLong(metadata.get("revision"), hit.get("file_revision"))) {
            mapper.deleteDedupeByPath(ownerId.toString(), path);
            throw new FileStorageException(404, "未命中");
        }
        long size = longValue(metadata.get("size_bytes"));
        return mapOf("uploaded", mapOf("path", path, "size", size, "deduped", true));
    }

    /**
     * 解析可读的 owner 文件路径。
     * <p>除路径安全检查外，还要求物理文件和 metadata 同时存在，并使用 {@code NOFOLLOW_LINKS}
     * 确认目标是普通文件；返回的路径可直接交给下载或预览读取。</p>
     * @param ownerId 文件所属 owner 的 UUID。
     * @param path owner 根下的相对文件路径。
     * @return 可供下载/预览读取的规范化绝对路径。
     * @throws FileStorageException owner 未认证、路径越界/含符号链接、文件或 metadata 不存在或目标不是普通文件时抛出。
     */
    @Override
    public Path fileForRead(UUID ownerId, String path) {
        return requireOwnedFile(ownerId, path);
    }

    /**
     * 在存储根目录创建受控的上传临时文件。
     * <p>临时文件不属于任何 owner，文件名以 {@code .upload.} 开头；只有经过
     * {@link #publishUpload(UUID, String, String, Path, String, boolean)} 的临时文件校验才能发布。</p>
     * @return 根目录下的上传临时路径。
     * @throws FileStorageException 无法创建临时文件时抛出。
     */
    @Override
    public Path createUploadTemp() {
        try {
            return Files.createTempFile(root, ".upload.", ".tmp");
        } catch (IOException error) {
            throw new FileStorageException(500, "无法创建上传临时文件", error);
        }
    }

    /**
     * 校验上传临时文件并发布到 owner 目录。
     * <p>服务端流式计算 MD5 和 SHA-256 并校验大小；声明了 MD5 且已有同 owner、revision
     * 仍有效的 dedupe 命中时，不移动临时文件而直接返回去重结果。否则在 storage lock 内
     * 选择目标并移动文件：{@code noclobber} 使用序号名称且不覆盖，覆盖模式拒绝已有目录并
     * 替换其他已有目标。文件移动后写入 content、revision 和 verified dedupe 行，并可写入
     * {@code file.changed} outbox；storage lock 持有到数据库事务完成，失败时恢复发布前内容。</p>
     * @param ownerId 文件所属 owner 的 UUID。
     * @param directory owner 根下的相对目录。
     * @param filename 目标文件名，不得含路径分隔符。
     * @param tempFile 请求体写入的上传临时文件。
     * @param declaredMd5 客户端声明的 MD5，会与服务端重新计算值比较。
     * @param noclobber 为 true 时同名目标改用序号文件名，不覆盖已有文件。
     * @return 发布后的 owner 相对路径和大小；已命中时还包含 {@code deduped=true}。
     * @throws FileStorageException 临时文件不受信、超限、摘要不匹配、路径/类型冲突、无可用序号或发布失败时抛出。
     */
    @Override
    @Transactional
    public Map<String, Object> publishUpload(UUID ownerId, String directory, String filename,
                                              Path tempFile, String declaredMd5, boolean noclobber) {
        Path temp = validateTemp(tempFile);
        String md5 = validateMd5(declaredMd5, false);
        try {
            long size = Files.size(temp);
            if (size > maxUploadBytes) {
                throw new FileStorageException(413, "文件超过大小上限 " + (maxUploadBytes / (1024 * 1024)) + "MB");
            }
            Digests digests = digest(temp);
            if (!md5.isBlank() && !md5.equals(digests.md5())) {
                throw new FileStorageException(400, "md5 与实际文件内容不一致");
            }
            Map<String, Object> dedupe = mapper.selectDedupe(ownerId.toString(), digests.md5());
            if (!md5.isBlank() && dedupe != null && validDedupe(ownerId, dedupe)) {
                String hitPath = String.valueOf(dedupe.get("path"));
                long hitSize = longValue(mapper.selectByPath(ownerId.toString(), hitPath).get("size_bytes"));
                return mapOf("uploaded", mapOf("path", hitPath, "size", hitSize, "deduped", true),
                        "indexed", null);
            }
            String requested = joinPath(directory, filename);
            Path target = safePath(ownerId, requested, false);
            try (MutationScope mutation = mutationScope()) {
                if (noclobber) {
                    target = uniqueTarget(target);
                } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        && Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new FileStorageException(409, "目标是目录: " + requested);
                }
                syncParentMetadata(ownerId, target.getParent());
                VersionSnapshot snapshot = null;
                if (!noclobber && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    snapshot = prepareVersionSnapshot(ownerId, target);
                    mutation.onPublished(snapshot::commit, snapshot::rollback);
                    snapshot.persist(mapper);
                    String snapshotPath = relative(ownerId, target);
                    mutation.onPublished(
                            () -> pruneVersionSnapshots(ownerId, snapshotPath),
                            () -> { });
                }
                PublishedMove publication = new PublishedMove(ownerId, temp, target, false);
                mutation.onPublished(publication::commit, publication::rollback);
                publication.publish();
                mapper.upsertContent(ownerId.toString(), relative(ownerId, target), size, digests.md5(), digests.sha256());
                mapper.insertRevision(ownerId.toString(), relative(ownerId, target), size, digests.md5(), digests.sha256());
                Map<String, Object> metadata = mapper.selectByPath(ownerId.toString(), relative(ownerId, target));
                long revision = metadata == null || metadata.get("revision") == null
                        ? 1 : longValue(metadata.get("revision"));
                String publishedPath = relative(ownerId, target);
                mapper.upsertDedupe(ownerId.toString(), digests.md5(), publishedPath, revision, true);
                publishChange(ownerId, "upsert", List.of(publishedPath),
                        "file-change:" + ownerId + ":" + publishedPath + ":" + revision);
                mutation.complete();
                return mapOf("uploaded", mapOf("path", publishedPath, "size", size), "indexed", null);
            }
        } catch (FileStorageException error) {
            throw error;
        } catch (IOException error) {
            throw new FileStorageException(500, "发布文件失败", error);
        }
    }

    /**
     * 以 UTF-8 写入文本文件，并复用上传发布流程完成原子文件替换和 metadata 更新。
     * <p>内容先写入根目录上传临时文件；{@code overwrite=false} 时使用 noclobber 序号名，
     * {@code overwrite=true} 时替换同名普通文件。方法结束后无论成功失败都会尝试清理临时文件。</p>
     * @param ownerId 文件所属 owner 的 UUID。
     * @param path owner 根下的相对文件路径。
     * @param content 要写入的文本；空值按空文本处理。
     * @param overwrite 是否允许替换同名普通文件。
     * @return 发布后的路径和大小信息。
     * @throws FileStorageException 路径非法、目标类型冲突、发布或临时文件写入失败时抛出。
     */
    @Override
    @Transactional
    public Map<String, Object> writeText(UUID ownerId, String path, String content, boolean overwrite) {
        String normalized = normalizePath(path);
        if (normalized.isBlank() || normalized.endsWith("/")) {
            throw new FileStorageException(400, "文本文件路径不能为空");
        }
        int separator = normalized.lastIndexOf('/');
        String directory = separator < 0 ? "" : normalized.substring(0, separator);
        String filename = separator < 0 ? normalized : normalized.substring(separator + 1);
        Path temp = createUploadTemp();
        try {
            Files.writeString(temp, content == null ? "" : content, java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return publishUpload(ownerId, directory, filename, temp, "", !overwrite);
        } catch (IOException error) {
            throw new FileStorageException(500, "写入文本文件失败", error);
        } finally {
            discardTemp(temp);
        }
    }

    /**
     * 在 owner 文件树中创建目录并同步该目录的 metadata。
     * <p>操作在 storage lock 内执行；已有目录保持不变，普通文件或内部路径不能作为目标。
     * 此方法只更新 metadata，不单独发布 {@code file.changed} outbox 事件。</p>
     * @param ownerId 目录所属 owner 的 UUID。
     * @param path owner 根下的相对目录路径。
     * @return 新目录的 owner 相对路径。
     * @throws FileStorageException 路径非法、目标是普通文件或目录创建/metadata 写入失败时抛出。
     */
    @Override
    @Transactional
    public Map<String, Object> mkdir(UUID ownerId, String path) {
        String normalized = normalizePath(path);
        if (normalized.isBlank()) {
            throw new FileStorageException(400, "目录路径不能为空");
        }
        Path directory = safePath(ownerId, normalized, false);
        try (StorageLock ignored = storageLock()) {
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileStorageException(409, "目标不是目录");
            }
            Files.createDirectories(directory);
            mapper.upsertMetadata(ownerId.toString(), normalized, true, 0);
            return mapOf("created", normalized);
        } catch (IOException error) {
            throw new FileStorageException(500, "创建目录失败", error);
        }
    }

    /**
     * 在 owner 文件树内移动文件或目录并将目标视为可覆盖。
     * <p>实际移动、目标替换、metadata 前缀清理/刷新和 {@code file.changed} 事件由
     * {@link #movePath(UUID, String, String, boolean, String)} 完成；非空目录不能被目录目标覆盖。</p>
     * @param ownerId 源和目标所属 owner 的 UUID。
     * @param source 源相对路径。
     * @param destination 目标相对路径。
     * @return 源到目标的路径摘要。
     * @throws FileStorageException 路径非法、源不存在、非空目录冲突或移动/metadata 更新失败时抛出。
     */
    @Override
    @Transactional
    public Map<String, Object> rename(UUID ownerId, String source, String destination) {
        return movePath(ownerId, source, destination, true, "renamed");
    }

    /**
     * 将 owner 文件或目录移动到目标目录下的同名路径。
     * <p>目标路径由 {@code destinationDirectory} 加源文件名组成；{@code overwrite} 控制
     * 是否删除并替换已有目标，成功后会重建目标 metadata 前缀并发布文件变更事件。</p>
     * @param ownerId 源和目标所属 owner 的 UUID。
     * @param source 源相对路径。
     * @param destinationDirectory 目标目录相对路径。
     * @param overwrite 是否替换目标。
     * @return 源到目标目录的路径摘要。
     * @throws FileStorageException 路径非法、源不存在、目标冲突或移动/metadata 更新失败时抛出。
     */
    @Override
    @Transactional
    public Map<String, Object> move(UUID ownerId, String source, String destinationDirectory, boolean overwrite) {
        Path sourcePath = requireExisting(ownerId, source);
        String destination = joinPath(destinationDirectory, sourcePath.getFileName().toString());
        movePath(ownerId, source, destination, overwrite, "moved");
        return mapOf("moved", normalizePath(source) + " → " + normalizePath(destinationDirectory) + "/");
    }

    /**
     * 在 owner 内递归复制文件或目录树，并按选项处理目标冲突。
     * <p>复制在 storage lock 内进行；目录树先在 owner 根下的隐藏 staging 中完整构建并
     * fsync，再移动到目标位置。覆盖目标时先写入 durable 事务 marker 并移到隐藏 backup，
     * 进程崩溃后由启动恢复未提交的旧目标或清理已发布的 backup。完成后会删除目标 metadata
     * 前缀、重新扫描目标树并发布 {@code file.changed} 事件。源和复制过程中遇到的符号链接/
     * 特殊文件都会被拒绝。</p>
     * @param ownerId 源和目标所属 owner 的 UUID。
     * @param source 源相对路径。
     * @param destination 目标相对路径。
     * @param overwrite 是否替换已有目标。
     * @return 源到目标的路径摘要。
     * @throws FileStorageException 源/目标路径非法、目标冲突、遇到符号链接/特殊文件或复制失败时抛出。
     */
    @Override
    @Transactional
    public Map<String, Object> copy(UUID ownerId, String source, String destination, boolean overwrite) {
        Path sourcePath = requireExisting(ownerId, source);
        Path target = safePath(ownerId, destination, false);
        Path ownerRoot = ownerRoot(ownerId);
        if (sourcePath.equals(target)) {
            throw new FileStorageException(400, "源与目标相同");
        }
        if (target.equals(ownerRoot)) {
            throw new FileStorageException(400, "目标不能是 owner 根目录");
        }
        if (Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS) && target.startsWith(sourcePath)) {
            throw new FileStorageException(400, "目标不能位于源目录内部");
        }
        try (MutationScope mutation = mutationScope()) {
            recoverCopyTransactions(ownerId);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !overwrite) {
                throw new FileStorageException(409, "目标已存在");
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                rejectSpecial(target);
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)
                    != Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileStorageException(409, "文件和目录类型不兼容");
            }
            CopyTransaction transaction = stageAndPublishCopy(ownerId, sourcePath, target);
            mutation.onPublished(
                    () -> cleanupPublishedCopyStrict(transaction),
                    () -> rollbackPublishedCopy(transaction));
            mapper.deletePrefix(ownerId.toString(), relative(ownerId, target));
            refreshMetadata(ownerId, target);
            publishChange(ownerId, "copy", List.of(relative(ownerId, target)),
                    "file-copy:" + ownerId + ":" + relative(ownerId, target));
            mutation.complete();
            return mapOf("copied", normalizePath(source) + " → " + normalizePath(destination));
        } catch (IOException error) {
            throw new FileStorageException(500, "复制失败", error);
        }
    }

    /**
     * 将 owner 文件或目录以优先原子移动的方式放入带唯一 {@code trash_id} 的 owner 回收站。
     * <p>磁盘内容移动到 {@code &lt;owner root&gt;/.trash/&lt;trash_id&gt;} 后写入 trash 行，记录原路径
     * 和删除时的 revision，并发布 {@code delete} 文件变更事件。该方法本身不删除原路径
     * metadata 或 dedupe 行，后续索引/事件处理及清空回收站逻辑负责清理相关状态。</p>
     * @param ownerId 被删除内容所属 owner 的 UUID。
     * @param path owner 根下的相对路径。
     * @return 原路径、{@code trash_id}、回收站标识、大小和目录标识。
     * @throws FileStorageException 内容或 metadata 不存在、路径非法或回收站移动/记录失败时抛出。
     */
    @Override
    @Transactional
    public Map<String, Object> deleteToTrash(UUID ownerId, String path) {
        Path source = requireExisting(ownerId, path);
        String original = normalizePath(path);
        String trashId = UUID.randomUUID().toString();
        Path stored = trashRoot(ownerId).resolve(trashId);
        Map<String, Object> metadata = mapper.selectByPath(ownerId.toString(), original);
        long revision = metadata == null || metadata.get("revision") == null
                ? 1 : longValue(metadata.get("revision"));
        try (MutationScope mutation = mutationScope()) {
            PublishedMove publication = new PublishedMove(ownerId, source, stored, true);
            mutation.onPublished(publication::commit, publication::rollback);
            publication.publish();
            mapper.insertTrash(trashId, ownerId.toString(), original, ".trash/" + trashId, revision);
            publishChange(ownerId, "delete", List.of(original), "file-delete:" + ownerId + ":" + trashId);
            long size = Files.isRegularFile(stored, LinkOption.NOFOLLOW_LINKS) ? Files.size(stored) : 0;
            mutation.complete();
            return mapOf("path", original, "trash_id", trashId, "trash_path", trashId,
                    "deleted_at", Instant.now().toEpochMilli() / 1000.0, "size", size,
                    "is_dir", Files.isDirectory(stored, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException error) {
            throw new FileStorageException(500, "删除文件失败", error);
        }
    }

    /**
     * 列出 owner 回收站中仍有磁盘内容的条目。
     * <p>每条数据库记录的 {@code stored_path} 都必须解析到该 owner 的 {@code .trash} 子树；
     * 磁盘内容已经缺失的孤儿记录只跳过、不在此方法中删除。</p>
     * @param ownerId 回收站所属 owner 的 UUID。
     * @return 原路径、trash_id、删除时间、大小和目录标识列表。
     * @throws FileStorageException 回收站路径越界、包含符号链接或读取失败时抛出。
     */
    @Override
    public Map<String, Object> listTrash(UUID ownerId) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : mapper.selectTrash(ownerId.toString())) {
            String stored = String.valueOf(row.get("stored_path"));
            Path content = safeInternalTrash(ownerId, stored);
            try {
                if (!Files.exists(content, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                boolean isDir = Files.isDirectory(content, LinkOption.NOFOLLOW_LINKS);
                long size = isDir ? 0 : Files.size(content);
                items.add(mapOf("path", row.get("original_path"), "trash_id", row.get("trash_id"),
                        "trash_path", stored, "deleted_at", row.get("deleted_at"), "size", size,
                        "is_dir", isDir));
            } catch (IOException error) {
                throw new FileStorageException(500, "读取回收站失败", error);
            }
        }
        return mapOf("items", items);
    }

    /**
     * 将回收站条目以优先原子移动的方式恢复到原 owner 路径，并刷新恢复树的 metadata。
     * <p>查找支持 {@code trash_id} 及旧的 {@code trash_path} 标识；恢复要求归档内容存在且
     * 原路径不存在。移动完成后删除 trash 行、递归 upsert metadata，并发布 {@code upsert}
     * 文件变更事件；原路径占用时不会覆盖现有内容。</p>
     * @param ownerId 回收站条目所属 owner 的 UUID。
     * @param trashIdOrPath trash_id，兼容旧调用传入的 trash_path。
     * @return 恢复后的原 owner 相对路径。
     * @throws FileStorageException 标识/归档内容不存在、原路径已占用、路径不安全或恢复失败时抛出。
     */
    @Override
    @Transactional
    public Map<String, Object> restoreTrash(UUID ownerId, String trashIdOrPath) {
        if (trashIdOrPath == null || trashIdOrPath.isBlank()) {
            throw new FileStorageException(400, "trash_id 不能为空");
        }
        Map<String, Object> row = mapper.selectTrashByIdentifier(ownerId.toString(), trashIdOrPath);
        if (row == null) {
            throw new FileStorageException(404, "回收站条目不存在");
        }
        String original = String.valueOf(row.get("original_path"));
        Path target = safePath(ownerId, original, false);
        Path stored = safeInternalTrash(ownerId, String.valueOf(row.get("stored_path")));
        try (MutationScope mutation = mutationScope()) {
            if (!Files.exists(stored, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileStorageException(404, "回收站内容不存在");
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileStorageException(409, "原路径已存在");
            }
            PublishedMove publication = new PublishedMove(ownerId, stored, target, true);
            mutation.onPublished(publication::commit, publication::rollback);
            publication.publish();
            mapper.deleteTrash(ownerId.toString(), String.valueOf(row.get("trash_id")));
            refreshMetadata(ownerId, target);
            publishChange(ownerId, "upsert", List.of(original),
                    "file-restore:" + ownerId + ":" + row.get("trash_id"));
            mutation.complete();
            return mapOf("restored", original);
        } catch (FileStorageException error) {
            throw error;
        } catch (IOException error) {
            throw new FileStorageException(500, "恢复文件失败", error);
        }
    }

    /**
     * 删除 owner 回收站中的全部磁盘内容和对应 trash 行。
     * <p>只有当当前原路径不存在且 metadata revision 仍等于删除时记录的 revision 时，才删除
     * 该路径 metadata 前缀，避免清理后来重新创建的文件；每条记录都会发布 {@code delete}
     * 事件（配置了 outbox 时）。处理按条目进行，途中失败时可能只完成部分清空。</p>
     * @param ownerId 回收站所属 owner 的 UUID。
     * @return 实际删除的 trash 条目数量。
     * @throws FileStorageException 回收站路径非法或某个条目删除/状态清理失败时抛出，后续条目可能尚未处理。
     */
    @Override
    @Transactional
    public Map<String, Object> emptyTrash(UUID ownerId) {
        int removed = 0;
        for (Map<String, Object> row : mapper.selectTrash(ownerId.toString())) {
            String id = String.valueOf(row.get("trash_id"));
            Path stored = safeInternalTrash(ownerId, String.valueOf(row.get("stored_path")));
            try (StorageLock ignored = storageLock()) {
                if (Files.exists(stored, LinkOption.NOFOLLOW_LINKS)) {
                    deleteTree(stored);
                    removed++;
                }
                String original = String.valueOf(row.get("original_path"));
                Map<String, Object> current = mapper.selectByPath(ownerId.toString(), original);
                boolean pathExists;
                try {
                    pathExists = Files.exists(safePath(ownerId, original, false), LinkOption.NOFOLLOW_LINKS);
                } catch (FileStorageException invalidLegacyPath) {
                    pathExists = true;
                }
                if (!pathExists && (current == null || sameLong(current.get("revision"), row.get("file_revision")))) {
                    if (current != null) mapper.deletePrefix(ownerId.toString(), original);
                    deleteVersionSnapshots(ownerId, original);
                }
                mapper.deleteTrash(ownerId.toString(), id);
                publishChange(ownerId, "delete", List.of(original), "file-empty-trash:" + ownerId + ":" + id);
            } catch (IOException error) {
                throw new FileStorageException(500, "回收站仅部分清空", error);
            }
        }
        return mapOf("removed", removed);
    }

    /**
     * 清理 owner 回收站中超过保留期的条目。
     *
     * <p>每日维护不会调用 {@link #emptyTrash(UUID)}，而是先按数据库删除时间筛选候选，
     * 再在存储锁内删除磁盘内容、检查原路径 revision、删除回收站行并发布索引失效事件。
     * 因此同一路径后来重新创建文件时不会被旧回收站记录误删。</p>
     *
     * @param ownerId 回收站归属 owner 的 UUID。
     * @param retentionDays 回收站保留天数。
     * @return 实际删除的回收站记录数量及规范化后的保留天数。
     * @throws FileStorageException 回收站路径非法或清理 I/O 失败时抛出。
     */
    @Override
    @Transactional
    public Map<String, Object> cleanupTrash(UUID ownerId, int retentionDays) {
        if (ownerId == null) throw new IllegalArgumentException("ownerId must not be null");
        int days = Math.max(1, Math.min(retentionDays, 3650));
        double cutoff = Instant.now().minus(days, ChronoUnit.DAYS).toEpochMilli() / 1000.0;
        int removed = 0;
        for (Map<String, Object> row : mapper.selectExpiredTrash(ownerId.toString(), cutoff)) {
            String id = String.valueOf(row.get("trash_id"));
            Path stored = safeInternalTrash(ownerId, String.valueOf(row.get("stored_path")));
            try (StorageLock ignored = storageLock()) {
                if (Files.exists(stored, LinkOption.NOFOLLOW_LINKS)) {
                    deleteTree(stored);
                }
                String original = String.valueOf(row.get("original_path"));
                Map<String, Object> current = mapper.selectByPath(ownerId.toString(), original);
                boolean pathExists;
                try {
                    pathExists = Files.exists(safePath(ownerId, original, false), LinkOption.NOFOLLOW_LINKS);
                } catch (FileStorageException invalidLegacyPath) {
                    pathExists = true;
                }
                if (!pathExists && (current == null || sameLong(current.get("revision"), row.get("file_revision")))) {
                    if (current != null) mapper.deletePrefix(ownerId.toString(), original);
                    deleteVersionSnapshots(ownerId, original);
                }
                if (mapper.deleteTrash(ownerId.toString(), id) > 0) {
                    removed++;
                    publishChange(ownerId, "delete", List.of(original), "file-cleanup-trash:" + ownerId + ":" + id);
                }
            } catch (IOException error) {
                throw new FileStorageException(500, "回收站过期清理失败", error);
            }
        }
        return mapOf("removed", removed, "retention_days", days);
    }

    /** 永久删除已确认不再存在的原路径时同步回收其版本快照文件和元数据。 */
    private void deleteVersionSnapshots(UUID ownerId, String path) throws IOException {
        List<Map<String, Object>> rows = mapper.selectVersionSnapshots(ownerId.toString(), path, 1000);
        if (rows == null) return;
        for (Map<String, Object> row : rows) {
            Path snapshot = safeInternalVersion(ownerId, String.valueOf(row.get("snapshot_path")));
            Files.deleteIfExists(snapshot);
            mapper.deleteVersionSnapshot(ownerId.toString(), String.valueOf(row.get("version_id")));
        }
        forceDirectory(ownerRoot(ownerId).resolve(".versions"));
    }

    /**
     * 删除根目录下由 {@link #createUploadTemp()} 创建的上传临时文件。
     * <p>该清理是幂等的；空值、非根目录路径和文件名不以 {@code .upload.} 开头的路径都会
     * 被忽略，不会触及 owner 文件树。若匹配路径本身是符号链接，删除的是链接而不是其目标。</p>
     * @param tempFile 待清理的临时路径。
     * @throws FileStorageException 删除符合条件的临时文件失败时抛出。
     */
    @Override
    public void discardTemp(Path tempFile) {
        if (tempFile == null) {
            return;
        }
        try {
            Path candidate = tempFile.toAbsolutePath().normalize();
            if (candidate.getParent() != null && candidate.getParent().equals(root)
                    && candidate.getFileName().toString().startsWith(".upload.")) {
                Files.deleteIfExists(candidate);
            }
        } catch (IOException error) {
            throw new FileStorageException(500, "清理上传临时文件失败", error);
        }
    }

    /**
     * 为一次文件变更写入可选的 outbox 事件。
     * <p>事件类型固定为 {@code file.changed}，实体为 {@code file}，首个路径用于投递索引，
     * 完整路径列表和 action 放在 payload 中；空 outbox 或空路径列表时不产生副作用。</p>
     * @param ownerId 数据所属用户的唯一标识。
     * @param action 文件变更动作，例如 {@code upsert}、{@code move} 或 {@code delete}。
     * @param paths 已经发生变化的 owner 相对路径列表。
     * @param idempotencyKey 事件去重键。
     */
    private void publishChange(UUID ownerId, String action, List<String> paths, String idempotencyKey) {
        invalidateOverview(ownerId);
        if (outbox == null || paths == null || paths.isEmpty()) return;
        outbox.enqueue(ownerId, "file.changed", "file", paths.get(0), idempotencyKey,
                mapOf("action", action, "paths", paths));
    }

    /** 文件 metadata 或内容变化后使任务中心的索引统计立即失效。 */
    private void invalidateOverview(UUID ownerId) {
        if (tasks != null) tasks.invalidateOverview(ownerId);
    }

    /**
     * 获取覆盖当前 JVM 和其他进程的文件存储锁。
     * <p>先持有进程内 {@link ReentrantLock}，再打开根目录下的 {@code .storage.lock} 并取得
     * {@link FileLock}；返回对象关闭时释放文件锁、通道和进程锁。</p>
     * @return 持有完整存储锁的资源句柄。
     * @throws FileStorageException 无法打开锁文件或取得文件锁时抛出。
     */
    private StorageLock storageLock() {
        mutationLock.lock();
        try {
            FileChannel channel = FileChannel.open(
                    root.resolve(".storage.lock"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            );
            FileLock lock = channel.lock();
            return new StorageLock(channel, lock);
        } catch (IOException error) {
            mutationLock.unlock();
            throw new FileStorageException(500, "无法锁定文件存储", error);
        }
    }

    /**
     * 创建一次持有 storage lock 的文件与数据库联合变更范围。
     * @return 尚未发布磁盘变更的生命周期句柄。
     */
    private MutationScope mutationScope() {
        return new MutationScope(storageLock());
    }

    /** 在覆盖发布前把旧普通文件复制到 owner 私有版本区，并返回可补偿快照 artifact。 */
    private VersionSnapshot prepareVersionSnapshot(UUID ownerId, Path target) throws IOException {
        rejectSpecial(target);
        String path = relative(ownerId, target);
        Map<String, Object> metadata = mapper.selectByPath(ownerId.toString(), path);
        long sourceRevision = metadata == null || metadata.get("revision") == null
                ? 1 : longValue(metadata.get("revision"));
        Digests digests = digest(target);
        long size = Files.size(target);
        UUID id = UUID.randomUUID();
        Path versions = ownerRoot(ownerId).resolve(".versions");
        Files.createDirectories(versions);
        rejectSymlinkComponents(ownerRoot(ownerId), versions);
        Path snapshot = versions.resolve(id + ".bin");
        try {
            Files.copy(target, snapshot, LinkOption.NOFOLLOW_LINKS);
            forcePublishedPath(snapshot);
            forceDirectory(versions);
        } catch (IOException error) {
            Files.deleteIfExists(snapshot);
            throw error;
        }
        return new VersionSnapshot(ownerId, id, path, sourceRevision,
                ".versions/" + id + ".bin", snapshot, size, digests);
    }

    /** 提交后按固定上限回收最旧快照，避免版本能力无限吞噬 owner 存储空间。 */
    private void pruneVersionSnapshots(UUID ownerId, String path) throws IOException {
        List<Map<String, Object>> rows = mapper.selectVersionSnapshots(ownerId.toString(), path,
                MAX_VERSION_SNAPSHOTS + 32);
        if (rows == null || rows.size() <= MAX_VERSION_SNAPSHOTS) return;
        Path versions = ownerRoot(ownerId).resolve(".versions");
        for (int index = MAX_VERSION_SNAPSHOTS; index < rows.size(); index++) {
            Map<String, Object> row = rows.get(index);
            String id = String.valueOf(row.get("version_id"));
            Path snapshot = safeInternalVersion(ownerId, String.valueOf(row.get("snapshot_path")));
            Files.deleteIfExists(snapshot);
            mapper.deleteVersionSnapshot(ownerId.toString(), id);
        }
        forceDirectory(versions);
    }

    /** 可抛出 I/O 异常的文件提交或回滚动作。 */
    @FunctionalInterface
    private interface MutationAction {
        /**
         * 执行一次文件提交清理或回滚。
         * @throws IOException 文件恢复、删除或持久化失败时抛出。
         */
        void run() throws IOException;
    }

    /** 覆盖发布前的真实内容快照，提交后保留，回滚时删除文件和行。 */
    private final class VersionSnapshot {
        private final UUID ownerId;
        private final UUID id;
        private final String path;
        private final long sourceRevision;
        private final String snapshotPath;
        private final Path file;
        private final long size;
        private final Digests digests;

        private VersionSnapshot(UUID ownerId, UUID id, String path, long sourceRevision,
                                String snapshotPath, Path file, long size, Digests digests) {
            this.ownerId = ownerId;
            this.id = id;
            this.path = path;
            this.sourceRevision = sourceRevision;
            this.snapshotPath = snapshotPath;
            this.file = file;
            this.size = size;
            this.digests = digests;
        }

        private void persist(FileMapper fileMapper) {
            fileMapper.insertVersionSnapshot(id.toString(), ownerId.toString(), path, sourceRevision,
                    snapshotPath, size, digests.md5(), digests.sha256());
        }

        private void commit() {
            // The snapshot is the retained historical artifact; no commit cleanup is needed.
        }

        private void rollback() throws IOException {
            fileMapperDelete();
            Files.deleteIfExists(file);
            forceDirectory(file.getParent());
        }

        private void fileMapperDelete() {
            mapper.deleteVersionSnapshot(ownerId.toString(), id.toString());
        }
    }

    /**
     * 把可见文件变更与当前 Spring 事务的最终状态绑定，并在完成前持续持有 storage lock。
     * <p>无事务同步时 {@link #complete()} 立即提交；有事务同步时由 afterCompletion 决定清理
     * backup 或恢复旧内容。调用方在 complete 前抛错时，try-with-resources 会立即回滚。</p>
     */
    private final class MutationScope implements AutoCloseable {
        private final StorageLock storageLock;
        private final List<MutationAction> commitActions = new ArrayList<>();
        private final List<MutationAction> rollbackActions = new ArrayList<>();
        private boolean published;
        private boolean deferred;
        private boolean finished;

        /**
         * 保存当前变更独占持有的 storage lock。
         * @param storageLock 已取得的跨进程存储锁。
         */
        private MutationScope(StorageLock storageLock) {
            this.storageLock = storageLock;
        }

        /**
         * 登记磁盘可见性提交后的清理与回滚动作。
         * @param commitAction 数据库提交后清理隐藏 backup 的动作。
         * @param rollbackAction 数据库未提交时恢复发布前内容的动作。
         */
        private void onPublished(MutationAction commitAction, MutationAction rollbackAction) {
            this.commitActions.add(commitAction);
            this.rollbackActions.add(rollbackAction);
            this.published = true;
        }

        /** 标记数据库写入阶段成功，并按当前事务同步状态提交或延后文件收尾。 */
        private void complete() {
            if (finished || deferred) return;
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                deferred = true;
                try {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        /**
                         * 根据数据库事务终态提交或回滚磁盘变更，并始终释放 storage lock。
                         * @param status Spring 事务完成状态。
                         */
                        @Override
                        public void afterCompletion(int status) {
                            finishDeferred(status == TransactionSynchronization.STATUS_COMMITTED);
                        }
                    });
                } catch (RuntimeException error) {
                    deferred = false;
                    rollbackNow(error);
                    throw error;
                }
                return;
            }
            commitNow();
        }

        /**
         * 在 Spring 事务回调中完成文件收尾；回调发生在数据库终态之后，因此异常只记录日志。
         * @param committed 数据库事务是否已经提交。
         */
        private void finishDeferred(boolean committed) {
            if (finished) return;
            try {
                if (published) {
                    List<MutationAction> actions = committed ? commitActions : rollbackActions;
                    if (committed) {
                        for (MutationAction action : actions) action.run();
                    } else {
                        for (int index = actions.size() - 1; index >= 0; index--) {
                            actions.get(index).run();
                        }
                    }
                }
            } catch (Exception error) {
                if (committed) {
                    LOGGER.warn("数据库已提交但文件变更 artifact 清理失败，将保守保留: {}", root, error);
                } else {
                    LOGGER.error("数据库事务未提交且文件内容回滚失败: {}", root, error);
                }
            } finally {
                finished = true;
                try {
                    storageLock.close();
                } catch (RuntimeException error) {
                    LOGGER.error("事务完成后释放文件存储锁失败", error);
                }
            }
        }

        /** 无事务同步时提交文件变更；提交后的清理失败不把已发布操作改报为失败。 */
        private void commitNow() {
            try {
                if (published) {
                    for (MutationAction action : commitActions) action.run();
                }
            } catch (Exception error) {
                LOGGER.warn("文件变更已发布但 artifact 清理失败，将保守保留: {}", root, error);
            } finally {
                finished = true;
                try {
                    storageLock.close();
                } catch (RuntimeException error) {
                    LOGGER.error("文件变更提交后释放存储锁失败", error);
                }
            }
        }

        /**
         * 立即回滚文件变更并释放 storage lock。
         * @param original 触发回滚的原异常；回滚异常会附加到该异常。
         */
        private void rollbackNow(RuntimeException original) {
            try {
                if (published) {
                    for (int index = rollbackActions.size() - 1; index >= 0; index--) {
                        rollbackActions.get(index).run();
                    }
                }
            } catch (Exception rollbackError) {
                original.addSuppressed(rollbackError);
            } finally {
                finished = true;
                try {
                    storageLock.close();
                } catch (RuntimeException closeError) {
                    original.addSuppressed(closeError);
                }
            }
        }

        /** complete 前离开范围时恢复磁盘内容；已登记事务回调时由回调负责最终收尾。 */
        @Override
        public void close() {
            if (finished || deferred) return;
            RuntimeException failure = new FileStorageException(500, "文件变更未完成，已尝试恢复");
            rollbackNow(failure);
            if (failure.getSuppressed().length > 0) throw failure;
        }
    }

    /**
     * 把源路径发布到目标路径，并保留已有目标用于数据库回滚补偿。
     * <p>普通移动回滚时先把新目标移回源路径；上传发布不恢复一次性临时文件，只删除新目标。
     * 随后再把隐藏 backup 恢复为原目标。</p>
     */
    private final class PublishedMove {
        private final Path ownerRoot;
        private final Path source;
        private final Path target;
        private final Path backup;
        private final boolean restoreSource;
        private boolean backupMoved;
        private boolean sourceMoved;

        /**
         * 创建一次尚未执行的可补偿移动。
         * @param ownerId 源和目标所属 owner。
         * @param source 要发布的源路径。
         * @param target 对外可见目标路径。
         * @param restoreSource 回滚时是否把新目标移回源路径。
         */
        private PublishedMove(UUID ownerId, Path source, Path target, boolean restoreSource) {
            this.ownerRoot = ownerRoot(ownerId);
            this.source = source;
            this.target = target;
            this.backup = this.ownerRoot.resolve(COPY_OLD_PREFIX + UUID.randomUUID());
            this.restoreSource = restoreSource;
        }

        /**
         * 先隐藏已有目标，再以独占移动发布源路径。
         * @throws IOException 创建父目录、移动或 fsync 失败时抛出。
         */
        private void publish() throws IOException {
            createParent(target);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                moveIntoPlace(target, backup, true);
                backupMoved = true;
                forceDirectory(ownerRoot);
            }
            moveIntoPlace(source, target, true);
            sourceMoved = true;
            forcePublishedPath(target);
        }

        /**
         * 数据库提交后删除隐藏旧目标。
         * @throws IOException backup 删除或 owner 目录 fsync 失败时抛出。
         */
        private void commit() throws IOException {
            if (!backupMoved) return;
            validateCopyArtifact(backup);
            deleteTree(backup);
            forceDirectory(ownerRoot);
        }

        /**
         * 数据库未提交时撤销新目标，并恢复源路径及被覆盖的旧目标。
         * @throws IOException 无法证明路径未被并发占用或移动/删除失败时抛出。
         */
        private void rollback() throws IOException {
            if (sourceMoved) {
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("文件回滚时已发布目标不存在: " + target);
                }
                if (restoreSource) {
                    if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("文件回滚时源路径已被占用: " + source);
                    }
                    createParent(source);
                    moveIntoPlace(target, source, true);
                    forcePublishedPath(source);
                } else {
                    deleteTree(target);
                    forceDirectory(target.getParent());
                }
            }
            if (backupMoved) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("文件回滚时目标与 backup 同时存在: " + target);
                }
                validateCopyArtifact(backup);
                createParent(target);
                moveIntoPlace(backup, target, true);
                forcePublishedPath(target);
            }
        }
    }

    /**
     * 封装一次文件存储操作持有的 JVM 锁和操作系统文件锁。
     * <p>该资源只能通过 try-with-resources 关闭；关闭时无论释放文件锁是否报错，都会释放
     * 外层的进程内 mutation lock。</p>
     */
    private final class StorageLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        /**
         * 保存已打开的锁文件通道和文件锁。
         * @param channel 根目录 {@code .storage.lock} 的通道。
         * @param lock 该通道上取得的操作系统文件锁。
         */
        private StorageLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        /**
         * 释放文件锁、锁文件通道和当前线程持有的 mutation lock。
         * @throws FileStorageException 释放文件锁或关闭通道失败时抛出；mutation lock 仍会释放。
         */
        @Override
        public void close() {
            try {
                lock.release();
                channel.close();
            } catch (IOException error) {
                throw new FileStorageException(500, "无法释放文件存储锁", error);
            } finally {
                mutationLock.unlock();
            }
        }
    }

    /**
     * 在两个已校验的 owner 路径之间执行受锁移动，并同步目标 metadata。
     * <p>源和目标相同会被拒绝；未启用覆盖时目标存在即冲突，两个目录之间覆盖时目标必须
     * 为空。已有目标会先递归删除，再以 {@link #moveIntoPlace(Path, Path, boolean)} 移动；
     * 源/目标 metadata 前缀会清理，目标树随后重新 upsert，并发布一条包含两条路径的变更事件。</p>
     * @param ownerId 数据所属用户的唯一标识。
     * @param source 源 owner 相对路径。
     * @param destination 目标 owner 相对路径。
     * @param overwrite 是否允许删除并替换已有目标。
     * @param resultKey 返回 Map 中保存路径摘要的键。
     * @return 以 {@code resultKey} 保存源到目标摘要的结果 Map。
     */
    private Map<String, Object> movePath(UUID ownerId, String source, String destination,
                                         boolean overwrite, String resultKey) {
        Path sourcePath = requireExisting(ownerId, source);
        Path target = safePath(ownerId, destination, false);
        Path ownerRoot = ownerRoot(ownerId);
        String sourceRelative = relative(ownerId, sourcePath);
        String targetRelative = relative(ownerId, target);
        try (MutationScope mutation = mutationScope()) {
            if (sourcePath.equals(target)) {
                throw new FileStorageException(400, "源与目标相同");
            }
            if (sourcePath.equals(ownerRoot) || target.equals(ownerRoot)) {
                throw new FileStorageException(400, "不能移动 owner 根目录");
            }
            if (Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS) && target.startsWith(sourcePath)) {
                throw new FileStorageException(400, "目标不能位于源目录内部");
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !overwrite) {
                throw new FileStorageException(409, "目标已存在");
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                rejectSpecial(target);
                boolean sourceDirectory = Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS);
                boolean targetDirectory = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS);
                if (sourceDirectory != targetDirectory) {
                    throw new FileStorageException(409, "文件和目录类型不兼容");
                }
            }
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                    && Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> children = Files.list(target)) {
                    if (children.findAny().isPresent()) {
                        throw new FileStorageException(409, "不能覆盖非空目录");
                    }
                }
            }
            PublishedMove publication = new PublishedMove(ownerId, sourcePath, target, true);
            mutation.onPublished(publication::commit, publication::rollback);
            publication.publish();
            // Tracking rows are path keyed; move them with the visible tree while the
            // same transaction still protects the filesystem and metadata update.
            mapper.deleteFavoritePrefix(ownerId.toString(), targetRelative);
            mapper.deleteAccessPrefix(ownerId.toString(), targetRelative);
            mapper.moveFavoritePrefix(ownerId.toString(), sourceRelative, targetRelative);
            mapper.moveAccessPrefix(ownerId.toString(), sourceRelative, targetRelative);
            mapper.moveVersionSnapshotPrefix(ownerId.toString(), sourceRelative, targetRelative);
            mapper.deletePrefix(ownerId.toString(), sourceRelative);
            mapper.deletePrefix(ownerId.toString(), targetRelative);
            refreshMetadata(ownerId, target);
            publishChange(ownerId, "move", List.of(sourceRelative, targetRelative),
                    "file-move:" + ownerId + ":" + sourceRelative + "->" + targetRelative);
            mutation.complete();
            return mapOf(resultKey, normalizePath(source) + " → " + normalizePath(destination));
        } catch (FileStorageException error) {
            throw error;
        } catch (IOException error) {
            throw new FileStorageException(500, "移动文件失败", error);
        }
    }

    /**
     * 将物理文件或目录树的当前状态 upsert 到 owner metadata 表。
     * <p>目录会先递归处理所有子项，再处理目录本身；它不负责删除旧的 metadata 前缀，
     * 因此移动或复制调用方会先自行清理旧目标。</p>
     * @param ownerId 数据所属用户的唯一标识。
     * @param path 要同步的物理文件或目录路径。
     * @throws IOException 遍历或读取文件系统失败时抛出。
     */
    private void refreshMetadata(UUID ownerId, Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> paths = Files.walk(path)) {
                paths.filter(candidate -> !candidate.equals(path)).forEach(candidate -> {
                    try {
                        refreshOne(ownerId, candidate);
                    } catch (IOException error) {
                        throw new FileStorageException(500, "同步文件元数据失败", error);
                    }
                });
            }
        }
        refreshOne(ownerId, path);
    }

    /**
     * 以物理文件状态 upsert 一条 owner metadata。
     * <p>目录的大小记录为 0，普通文件记录当前字节数；符号链接和其他特殊类型不会写入。</p>
     * @param ownerId 数据所属用户的唯一标识。
     * @param path 要同步的物理路径。
     * @throws IOException 读取普通文件大小失败时抛出。
     */
    private void refreshOne(UUID ownerId, Path path) throws IOException {
        rejectSpecial(path);
        mapper.upsertMetadata(ownerId.toString(), relative(ownerId, path),
                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS),
                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? 0 : Files.size(path));
    }

    /**
     * 解析同时存在于磁盘和 metadata 表中的 owner 路径。
     * <p>路径先经过 owner 根、越界、内部路径和符号链接检查，再用 {@code NOFOLLOW_LINKS}
     * 检查物理对象和 Mapper metadata；任一侧缺失都按文件不存在处理。</p>
     * @param ownerId 数据所属用户的唯一标识。
     * @param path owner 根下的相对路径。
     * @return 已通过 owner 安全检查的绝对物理路径。
     */
    private Path requireExisting(UUID ownerId, String path) {
        Path candidate = safePath(ownerId, path, false);
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileStorageException(404, "文件不存在");
        }
        rejectSpecial(candidate);
        if (mapper.selectByPath(ownerId.toString(), relative(ownerId, candidate)) == null) {
            throw new FileStorageException(404, "文件不存在");
        }
        return candidate;
    }

    /**
     * 要求 {@link #requireExisting(UUID, String)} 返回普通文件。
     * @param ownerId 数据所属用户的唯一标识。
     * @param path owner 根下的相对文件路径。
     * @return 已通过 owner 安全检查的普通文件绝对路径。
     */
    private Path requireOwnedFile(UUID ownerId, String path) {
        Path candidate = requireExisting(ownerId, path);
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileStorageException(404, "文件不存在");
        }
        return candidate;
    }

    /**
     * 返回并初始化一个 owner 的磁盘根目录。
     * <p>owner ID 必须非空；目录由 {@code root.resolve(ownerId)} 生成并规范化，必须仍位于
     * 全局 root 下。目录创建后检查 root 到 owner 之间的每个实际组件，拒绝符号链接。</p>
     * @param ownerId 数据所属用户的唯一标识。
     * @return owner 的绝对规范根路径。
     */
    private Path ownerRoot(UUID ownerId) {
        if (ownerId == null) {
            throw new FileStorageException(401, "authentication required");
        }
        Path ownerRoot = root.resolve(ownerId.toString()).normalize();
        if (!ownerRoot.startsWith(root)) {
            throw new FileStorageException(403, "路径越界");
        }
        try {
            Files.createDirectories(ownerRoot);
        } catch (IOException error) {
            throw new FileStorageException(500, "无法初始化 owner 文件存储", error);
        }
        rejectSymlinkComponents(root, ownerRoot);
        return ownerRoot;
    }

    /**
     * 返回并初始化 owner 私有的回收站目录。
     * <p>回收站固定为 owner 根下的 {@code .trash}，创建后再次检查从 owner 根到该目录的
     * 所有组件，避免内部目录被符号链接替换。</p>
     * @param ownerId 数据所属用户的唯一标识。
     * @return owner 的回收站绝对路径。
     */
    private Path trashRoot(UUID ownerId) {
        Path trash = ownerRoot(ownerId).resolve(".trash");
        try {
            Files.createDirectories(trash);
        } catch (IOException error) {
            throw new FileStorageException(500, "无法初始化回收站", error);
        }
        rejectSymlinkComponents(ownerRoot(ownerId), trash);
        return trash;
    }

    /**
     * 将外部 owner 相对路径解析为安全的物理路径。
     * <p>空路径返回 owner 根；其他路径必须不是绝对路径、规范化后不能以 {@code ..} 开头，
     * 首段不能是内部名称，解析结果必须仍位于 owner 根，并且从根到目标的每个已有组件都
     * 不能是符号链接。{@code allowRoot} 是保留的兼容参数；当前实现对空路径仍返回 owner 根。</p>
     * @param ownerId 数据所属用户的唯一标识。
     * @param raw 外部传入的相对路径。
     * @param allowRoot 兼容调用方保留的根路径选项，当前实现不改变上述解析规则。
     * @return owner 根下的绝对规范路径。
     */
    private Path safePath(UUID ownerId, String raw, boolean allowRoot) {
        Path base = ownerRoot(ownerId);
        String normalized = normalizePath(raw);
        if (normalized.isBlank()) {
            return base;
        }
        Path relative = Path.of(normalized).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new FileStorageException(403, "路径越界");
        }
        if (relative.getNameCount() > 0 && isInternal(relative.getName(0).toString())) {
            throw new FileStorageException(403, "内部存储路径不可访问");
        }
        Path candidate = base.resolve(relative).normalize();
        if (!candidate.startsWith(base)) {
            throw new FileStorageException(403, "路径越界");
        }
        rejectSymlinkComponents(base, candidate);
        return candidate;
    }

    /**
     * 将数据库中的回收站存储路径限制在当前 owner 的 {@code .trash} 子树内。
     * <p>规范化后的候选路径必须位于回收站目录且不能等于回收站本身，同时拒绝沿途符号链接；
     * 该方法是读取、恢复和清空回收站时的内部路径边界。</p>
     * @param ownerId 数据所属用户的唯一标识。
     * @param stored 数据库保存的 owner 相对存储路径，例如 {@code .trash/&lt;trash_id&gt;}。
     * @return 回收站内的绝对规范路径。
     */
    private Path safeInternalTrash(UUID ownerId, String stored) {
        Path base = ownerRoot(ownerId);
        Path trash = base.resolve(".trash").normalize();
        Path candidate = base.resolve(stored.replace('\\', '/')).normalize();
        if (!candidate.startsWith(trash) || candidate.equals(trash)) {
            throw new FileStorageException(403, "非法回收站路径");
        }
        rejectSymlinkComponents(base, candidate);
        return candidate;
    }

    /** 将数据库中的版本快照路径限制在当前 owner 的 .versions 子树内。 */
    private Path safeInternalVersion(UUID ownerId, String stored) {
        Path base = ownerRoot(ownerId);
        Path versions = base.resolve(".versions").normalize();
        Path candidate = base.resolve(stored.replace('\\', '/')).normalize();
        if (!candidate.startsWith(versions) || candidate.equals(versions)) {
            throw new FileStorageException(403, "非法版本快照路径");
        }
        rejectSymlinkComponents(base, candidate);
        return candidate;
    }

    /**
     * 检查 base 到 candidate 之间的每个路径组件是否为符号链接。
     * @param base 已确认安全的路径基准。
     * @param candidate 要访问、创建或变更的候选路径。
     * @throws FileStorageException 任一路径组件是符号链接时抛出。
     */
    private void rejectSymlinkComponents(Path base, Path candidate) {
        Path current = base;
        for (Path part : base.relativize(candidate)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new FileStorageException(403, "路径越界(符号链接)");
            }
        }
    }

    /**
     * 拒绝符号链接以及目录、普通文件之外的文件系统对象。
     * @param path 待访问或写入的物理路径。
     * @throws FileStorageException 路径是符号链接或特殊文件时抛出。
     */
    private void rejectSpecial(Path path) {
        if (Files.isSymbolicLink(path)
                || (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
            throw new FileStorageException(403, "不支持的文件类型");
        }
    }

    /**
     * 判断名称是否属于不应出现在 owner 可见文件树中的内部名称。
     * <p>内部集合包含 {@code .index}、{@code .trash}、{@code .storage.lock}，以及上传、复制
     * staging 和旧 staging 的相应前缀；公共路径检查使用它限制首段，目录列表使用它隐藏直接子项。</p>
     * @param name 单个路径组件名称。
     * @return 名称是否为内部存储名称。
     */
    private boolean isInternal(String name) {
        return INTERNAL_NAMES.contains(name) || name.startsWith(".upload.")
                || name.startsWith(".copy.") || name.startsWith(".copy-old.");
    }

    /**
     * 计算物理路径相对于 owner 根的稳定、正斜杠分隔路径。
     * @param ownerId 数据所属用户的唯一标识。
     * @param path owner 根下的绝对或可规范化物理路径。
     * @return owner 相对路径字符串。
     */
    private String relative(UUID ownerId, Path path) {
        return ownerRoot(ownerId).relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /**
     * 规范化外部路径文本的分隔符和首尾斜杠。
     * <p>{@code null} 或空白输入变为空字符串；该方法不消除中间的 {@code ..}，最终越界判断
     * 由 {@link #safePath(UUID, String, boolean)} 在 {@link Path#normalize()} 后完成。</p>
     * @param raw 外部路径文本。
     * @return 去除首尾空白/斜杠并统一为正斜杠的路径文本。
     */
    private String normalizePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.trim().replace('\\', '/').replaceAll("^/+|/+$", "");
    }

    /**
     * 拼接目录和单个文件名，形成待交给 owner 路径校验的相对路径。
     * <p>文件名不能为空且不能含路径分隔符；目录本身的越界和内部路径检查由后续
     * {@link #safePath(UUID, String, boolean)} 执行。</p>
     * @param directory owner 根下的目录文本。
     * @param filename 不含分隔符的文件名。
     * @return 目录和文件名拼接后的路径文本。
     */
    private String joinPath(String directory, String filename) {
        String dir = normalizePath(directory);
        String name = filename == null ? "" : filename.trim().replace('\\', '/');
        if (name.isBlank() || name.contains("/")) {
            throw new FileStorageException(400, "文件名无效");
        }
        return dir.isBlank() ? name : dir + "/" + name;
    }

    /**
     * 提取文件名的小写扩展名。
     * @param file 文件路径。
     * @return 最后一个点及其后的扩展名；没有扩展名时为空字符串。
     */
    private String suffix(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index);
    }

    /**
     * 根据扩展名把文件归类为预览类型。
     * <p>已知媒体扩展名返回 {@code image}、{@code video} 或 {@code audio}，PDF 返回
     * {@code pdf}，文本扩展名和无扩展名返回 {@code text}，其余返回 {@code binary}。</p>
     * @param suffix 小写扩展名。
     * @return 文件预览类型标识。
     */
    private String previewKind(String suffix) {
        if (MEDIA_TYPES.getOrDefault(suffix, "").startsWith("image/")) return "image";
        if (MEDIA_TYPES.getOrDefault(suffix, "").startsWith("video/")) return "video";
        if (MEDIA_TYPES.getOrDefault(suffix, "").startsWith("audio/")) return "audio";
        if (".pdf".equals(suffix)) return "pdf";
        return TEXT_SUFFIXES.contains(suffix) || suffix.isBlank() ? "text" : "binary";
    }

    /**
     * 把索引表聚合字段转换成文件页可直接展示的状态对象。
     * 当前模型指纹为空时，已有旧向量会标记为 stale，避免在未配置模型时误报“已向量化”。
     *
     * @param ownerId 文件 owner UUID。
     * @param path 文件相对路径。
     * @param fingerprint 当前 embedding 配置指纹，可为空。
     * @return 文本索引、chunk 数量、当前有效向量数量和机器状态。
     */
    private Map<String, Object> indexStatus(UUID ownerId, String path, String fingerprint) {
        Map<String, Object> raw = mapper.selectIndexStatus(ownerId.toString(), path, fingerprint);
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw == null) {
            result.put("text_indexed", false);
            result.put("vision_indexed", false);
            result.put("vector_type", null);
            result.put("vectorized", false);
            result.put("vector_status", "not_indexed");
            result.put("chunk_count", 0);
            result.put("text_chunk_count", 0);
            result.put("vision_chunk_count", 0);
            result.put("vector_chunks", 0);
            result.put("text_vector_chunks", 0);
            result.put("vision_vector_chunks", 0);
            result.put("stored_vector_chunks", 0);
            result.put("text_stored_vector_chunks", 0);
            result.put("vision_stored_vector_chunks", 0);
            result.put("embedding_configured", fingerprint != null);
            return result;
        }
        boolean textIndexed = Boolean.TRUE.equals(raw.get("text_indexed"));
        boolean visionIndexed = Boolean.TRUE.equals(raw.get("vision_indexed"));
        int textChunks = intValue(raw.get("text_chunk_count"));
        int visionChunks = intValue(raw.get("vision_chunk_count"));
        int chunks = intValue(raw.get("chunk_count"));
        int stored = intValue(raw.get("stored_vector_chunks"));
        int current = intValue(raw.get("vector_chunks"));
        int textCurrent = intValue(raw.get("text_vector_chunks"));
        int visionCurrent = intValue(raw.get("vision_vector_chunks"));
        String status;
        if (!textIndexed && !visionIndexed) status = "not_indexed";
        else if (chunks == 0) status = "indexed";
        else if (fingerprint == null) status = stored > 0 ? "stale" : "not_configured";
        else if (current == chunks) status = "vectorized";
        else if (current > 0) status = "partial";
        else if (stored > 0) status = "stale";
        else status = "pending";
        result.put("text_indexed", textIndexed);
        result.put("vision_indexed", visionIndexed);
        result.put("vector_type", textIndexed && visionIndexed ? "mixed"
                : visionIndexed ? "vision" : textIndexed ? "text" : null);
        result.put("vectorized", "vectorized".equals(status));
        result.put("vector_status", status);
        result.put("chunk_count", chunks);
        result.put("text_chunk_count", textChunks);
        result.put("vision_chunk_count", visionChunks);
        result.put("vector_chunks", current);
        result.put("text_vector_chunks", textCurrent);
        result.put("vision_vector_chunks", visionCurrent);
        result.put("stored_vector_chunks", stored);
        result.put("text_stored_vector_chunks", intValue(raw.get("text_stored_vector_chunks")));
        result.put("vision_stored_vector_chunks", intValue(raw.get("vision_stored_vector_chunks")));
        result.put("embedding_configured", fingerprint != null);
        return result;
    }

    /**
     * 组织文件详情面板所需的抽取文档、chunk 和向量解释信息。
     * 不把 pgvector 数值返回给客户端，只返回可读正文、版本、模型元数据和当前向量命中状态；
     * 超过展示上限时保留 truncated 标志，避免打开大文件拖垮文件详情请求。
     *
     * @param ownerId 文件 owner UUID。
     * @param path 文件相对路径。
     * @param fingerprint 当前 embedding 配置指纹。
     * @return 可直接序列化给文件详情面板的索引详情。
     */
    private Map<String, Object> indexDetails(UUID ownerId, String path, String fingerprint) {
        List<Map<String, Object>> rows = mapper.selectIndexDetails(
                ownerId.toString(), path, fingerprint, INDEX_DETAIL_LIMIT + 1);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("available", false);
        detail.put("chunks", List.of());
        detail.put("truncated", false);
        if (rows == null || rows.isEmpty()) return detail;

        Map<String, Object> first = rows.get(0);
        String documentId = stringValue(first.get("document_id"));
        if (documentId.isBlank()) return detail;

        detail.put("available", true);
        detail.put("document_id", documentId);
        detail.put("vector_type", stringValue(first.get("vector_type")));
        detail.put("source_revision", first.get("source_revision"));
        detail.put("extractor_version", first.get("extractor_version"));
        detail.put("updated", first.get("updated"));
        detail.put("embedding_fingerprint", fingerprint);
        if (embeddingConfigs != null) {
            embeddingConfigs.find(ownerId).ifPresent(config -> {
                detail.put("embedding_provider", config.provider());
                detail.put("embedding_model", config.model());
            });
        }

        boolean truncated = rows.size() > INDEX_DETAIL_LIMIT;
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (Map<String, Object> row : rows.subList(0, Math.min(rows.size(), INDEX_DETAIL_LIMIT))) {
            String chunkId = stringValue(row.get("chunk_id"));
            if (chunkId.isBlank()) continue;
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", chunkId);
            chunk.put("vector_type", stringValue(row.get("vector_type")));
            chunk.put("index", row.get("chunk_index"));
            chunk.put("chunk_version", row.get("chunk_version"));
            chunk.put("source_revision", row.get("chunk_source_revision"));
            chunk.put("content", row.get("content"));
            chunk.put("content_length", row.get("content_length"));
            chunk.put("stored_vector", Boolean.TRUE.equals(row.get("stored_vector")));
            chunk.put("current_vector", Boolean.TRUE.equals(row.get("current_vector")));
            chunk.put("embedding_fingerprint", row.get("embedding_fingerprint"));
            chunks.add(chunk);
        }
        detail.put("chunks", chunks);
        detail.put("truncated", truncated);
        return detail;
    }

    /**
     * 把数据库可空字段转换为稳定的非空文本，避免详情响应出现字符串“null”。
     * @param value 数据库字段值。
     * @return 非空文本；空值返回空字符串。
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 读取当前 owner 的 embedding 配置并生成与向量任务一致的 fingerprint。
     * API key 不参与指纹，避免换密钥导致内容必须重新向量化。
     *
     * @param ownerId 文件 owner UUID。
     * @return 当前配置指纹；没有配置或依赖不可用时返回 null。
     */
    private String currentEmbeddingFingerprint(UUID ownerId) {
        if (embeddingConfigs == null) return null;
        return embeddingConfigs.find(ownerId).map(config -> EmbeddingFingerprint.of(
                config.provider(), config.baseUrl(), config.model())).orElse(null);
    }

    /**
     * 判断相对路径是否包含内部存储组件。
     *
     * @param path owner 相对路径。
     * @return 任一组件是 staging、锁或索引目录时为 true。
     */
    private boolean hasInternalComponent(String path) {
        for (String component : path.split("/")) {
            if (isInternal(component)) return true;
        }
        return false;
    }

    /**
     * 读取 JDBC 聚合结果中的 int 字段。
     *
     * @param value Number、数字文本或 null。
     * @return 转换后的整数，null 按 0 处理。
     */
    private int intValue(Object value) {
        return value == null ? 0 : value instanceof Number number
                ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    /**
     * 读取文件开头的有限字节并按兼容编码转换为预览文本。
     * <p>最多读取 {@code maxChars} 个字节，编码尝试顺序为 UTF-8、GBK、ISO-8859-1，
     * 不会把完整文件载入内存；该方法不负责再次判断扩展名是否为文本。</p>
     *
     * @param file 待读取的物理文件
     * @param maxChars 最多读取的字节数。
     * @return 读取并解码的预览文本；所有候选编码都失败时返回二进制提示。
     * @throws IOException 无法打开或读取文件时抛出。
     */
    private String readTextPreview(Path file, int maxChars) throws IOException {
        int byteLimit = Math.max(1, maxChars);
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            bytes = input.readNBytes(byteLimit + 1);
        }
        boolean truncated = bytes.length > byteLimit;
        if (truncated) bytes = Arrays.copyOf(bytes, byteLimit);
        return decodeTextPreview(bytes, truncated);
    }

    /**
     * 严格尝试 UTF-8、GBK 和 ISO-8859-1，避免替换字符让首个编码永远伪成功。
     * 截断预览允许末尾保留一个未完成码点，但正文中的坏字节仍会触发下一编码。
     * @param bytes 要解码的预览字节。
     * @param truncated 字节是否因预览上限被截断。
     * @return 首个严格匹配候选编码得到的文本。
     */
    static String decodeTextPreview(byte[] bytes, boolean truncated) {
        for (Charset charset : List.of(StandardCharsets.UTF_8, Charset.forName("GBK"), StandardCharsets.ISO_8859_1)) {
            try {
                return decodeStrict(bytes, charset, !truncated);
            } catch (CharacterCodingException ignored) {
                // Try the next compatibility encoding.
            }
        }
        throw new IllegalStateException("ISO-8859-1 decoder rejected input");
    }

    /**
     * 使用 REPORT 模式执行一次字符集解码。
     * @param bytes 输入字节。
     * @param charset 候选字符集。
     * @param endOfInput 输入是否包含文件的真实结尾。
     * @return 解码后的文本。
     * @throws CharacterCodingException 输入包含候选字符集无法表示的字节时抛出。
     */
    private static String decodeStrict(byte[] bytes, Charset charset, boolean endOfInput)
            throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer input = ByteBuffer.wrap(bytes);
        int capacity = Math.max(1, (int) Math.ceil(bytes.length * decoder.maxCharsPerByte()));
        CharBuffer output = CharBuffer.allocate(capacity);
        CoderResult result = decoder.decode(input, output, endOfInput);
        if (result.isError()) result.throwException();
        if (endOfInput) {
            result = decoder.flush(output);
            if (result.isError()) result.throwException();
        }
        output.flip();
        return output.toString();
    }

    /**
     * 读取文件服务根所在 FileStore 的容量信息。
     * <p>{@code used} 是 {@code total - usable}，表示整个文件系统的估算值，不是当前 owner
     * 文件树的精确占用。</p>
     * @return 包含 {@code total}、{@code used} 和 {@code free} 字节数的 Map。
     * @throws IOException 无法读取根目录所在 FileStore 时抛出。
     */
    private Map<String, Object> diskUsage() throws IOException {
        FileStore store = Files.getFileStore(root);
        long total = store.getTotalSpace();
        long free = store.getUsableSpace();
        return mapOf("total", total, "used", Math.max(0, total - free), "free", free);
    }

    /**
     * 校验上传临时文件确实位于服务根目录并具有受控名称。
     * <p>要求路径规范化后直接位于 root、文件名以 {@code .upload.} 开头、不是符号链接且
     * 是普通文件；该检查防止调用方把 owner 文件或任意外部文件交给发布流程。</p>
     * @param temp 待发布的上传临时路径。
     * @return 通过检查的绝对规范路径。
     */
    private Path validateTemp(Path temp) {
        if (temp == null) throw new FileStorageException(400, "上传临时文件不能为空");
        Path candidate = temp.toAbsolutePath().normalize();
        if (!candidate.getParent().equals(root) || !candidate.getFileName().toString().startsWith(".upload.")
                || Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileStorageException(403, "非法上传临时文件");
        }
        return candidate;
    }

    /**
     * 为 noclobber 发布选择未占用的目标路径。
     * <p>首选原目标；冲突时保留扩展名并尝试 {@code -2} 到 {@code -21} 的名称。调用方在
     * storage lock 内使用该结果，使选择和随后的独占移动处于同一串行区间。</p>
     * @param target 已通过 owner 路径检查的目标路径。
     * @return 当前可用的目标路径。
     * @throws FileStorageException 21 个候选名称全部已存在时抛出。
     */
    private Path uniqueTarget(Path target) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return target;
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        Path parent = target.getParent();
        for (int i = 2; i <= 21; i++) {
            Path candidate = parent.resolve(stem + "-" + i + extension);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate;
        }
        throw new FileStorageException(409, "同名冲突过多");
    }

    /**
     * 将上传目标的 owner 父目录逐级同步到 metadata，供后续目录操作做归属校验。
     * @param ownerId 文件所属 owner。
     * @param parent 上传目标的物理父目录。
     */
    private void syncParentMetadata(UUID ownerId, Path parent) {
        Path ownerRoot = ownerRoot(ownerId);
        if (parent == null || parent.equals(ownerRoot) || !parent.startsWith(ownerRoot)) return;
        Path relative = ownerRoot.relativize(parent);
        Path current = ownerRoot;
        for (Path component : relative) {
            current = current.resolve(component);
            mapper.upsertMetadata(ownerId.toString(), relative(ownerId, current), true, 0);
        }
    }

    /**
     * 创建目标的父目录。
     * @param target 已通过 owner 路径检查的目标路径。
     * @throws IOException 创建父目录失败时抛出。
     */
    private void createParent(Path target) throws IOException {
        if (target.getParent() != null) Files.createDirectories(target.getParent());
    }

    /**
     * 将文件或目录移动到目标路径，优先使用原子移动。
     * <p>{@code exclusive=true} 时不覆盖目标；否则以 {@code REPLACE_EXISTING} 覆盖。文件系统
     * 不支持 {@code ATOMIC_MOVE} 时退回普通移动，因此调用方不能把 fallback 当作跨文件系统
     * 的原子提交。</p>
     * @param source 已通过安全检查的源路径。
     * @param target 已通过安全检查的目标路径。
     * @param exclusive 是否要求目标不存在。
     * @throws IOException 移动失败或独占目标已存在时抛出。
     */
    private void moveIntoPlace(Path source, Path target, boolean exclusive) throws IOException {
        try {
            if (exclusive) Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            else Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            if (exclusive) Files.move(source, target);
            else Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (FileAlreadyExistsException error) {
            throw error;
        }
    }

    /**
     * 在隐藏 staging 中构建并发布一次复制事务。
     * <p>staging 和 backup 与目标位于同一 owner 根，因此发布使用独占的原子移动；覆盖时
     * 目标先移到 backup，marker 在每个恢复点 durable 落盘。移动完成后即视为可见性提交点，
     * 之后的 fsync 或 marker/backup 清理失败只记录 warning，不把已发布内容伪报为复制失败。</p>
     * @param ownerId 源和目标所属 owner 的 UUID。
     * @param source 已通过 owner 路径检查的源路径。
     * @param target 已通过 owner 路径检查的目标路径。
     * @return 已发布的复制事务，供 metadata 更新成功后清理隐藏 artifact。
     * @throws IOException staging、marker 或文件移动失败时抛出。
     */
    private CopyTransaction stageAndPublishCopy(UUID ownerId, Path source, Path target) throws IOException {
        Path owner = ownerRoot(ownerId);
        String transactionId = UUID.randomUUID().toString();
        CopyTransaction transaction = new CopyTransaction(
                owner,
                owner.resolve(COPY_PREFIX + transactionId + COPY_MARKER_SUFFIX),
                owner.resolve(COPY_PREFIX + transactionId + ".staging"),
                owner.resolve(COPY_OLD_PREFIX + transactionId),
                target,
                "prepared"
        );
        writeCopyMarker(transaction, "prepared");
        try {
            copyTree(source, transaction.staging());
            forcePath(transaction.staging());

            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                moveIntoPlace(target, transaction.backup(), true);
                forceDirectory(owner);
            }
            writeCopyMarker(transaction, "backup_moved");
            createParent(target);
            moveIntoPlace(transaction.staging(), target, true);

            try {
                forcePath(target);
                forceDirectory(target.getParent());
            } catch (IOException error) {
                LOGGER.warn("复制已发布但无法完成目标 fsync: {}", target, error);
            }
            try {
                writeCopyMarker(transaction, "published");
            } catch (IOException error) {
                LOGGER.warn("复制已发布但无法更新事务 marker: {}", transaction.marker(), error);
            }
            return transaction;
        } catch (IOException | FileStorageException error) {
            try {
                rollbackCopyTransaction(transaction);
            } catch (IOException rollbackError) {
                error.addSuppressed(rollbackError);
            }
            throw error;
        }
    }

    /**
     * 启动时扫描每个 owner 根下的复制 marker，并恢复未提交事务或清理已提交 backup。
     * @throws IOException marker 损坏、恢复失败或目录读取失败时抛出。
     */
    private void recoverPendingCopyTransactions() throws IOException {
        try (Stream<Path> owners = Files.list(root)) {
            for (Path owner : owners.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path)).toList()) {
                try {
                    recoverCopyTransactions(UUID.fromString(owner.getFileName().toString()));
                } catch (IllegalArgumentException ignored) {
                    // The storage root may contain unrelated files; only UUID owner directories are recoverable.
                }
            }
        }
    }

    /**
     * 恢复一个 owner 根下所有复制事务。
     * @param ownerId 文件树所属 owner 的 UUID。
     * @throws IOException marker 读取、事务恢复或 artifact 清理失败时抛出。
     */
    private void recoverCopyTransactions(UUID ownerId) throws IOException {
        Path owner = ownerRoot(ownerId);
        List<Path> markers;
        try (Stream<Path> paths = Files.list(owner)) {
            markers = paths.filter(path -> isCopyMarker(path.getFileName().toString())).toList();
        }
        for (Path marker : markers) {
            recoverCopyTransaction(readCopyTransaction(owner, marker));
        }
    }

    /**
     * 按 marker 状态恢复单次复制。
     * @param transaction 已校验过路径边界的复制事务。
     * @throws IOException 恢复过程无法证明新旧目标状态时抛出，保留 marker 等待下一次人工处理。
     */
    private void recoverCopyTransaction(CopyTransaction transaction) throws IOException {
        validateCopyArtifact(transaction.staging());
        validateCopyArtifact(transaction.backup());
        if (Files.exists(transaction.target(), LinkOption.NOFOLLOW_LINKS)) {
            rejectSpecial(transaction.target());
        }

        if ("prepared".equals(transaction.state())) {
            if (Files.exists(transaction.backup(), LinkOption.NOFOLLOW_LINKS)) {
                restoreCopyBackup(transaction);
            }
            deleteTree(transaction.staging());
            if (!Files.exists(transaction.backup(), LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(transaction.marker());
                forceDirectory(transaction.ownerRoot());
            }
            return;
        }

        if ("backup_moved".equals(transaction.state())) {
            if (!Files.exists(transaction.target(), LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(transaction.staging(), LinkOption.NOFOLLOW_LINKS)) {
                    createParent(transaction.target());
                    moveIntoPlace(transaction.staging(), transaction.target(), true);
                    forcePublishedPath(transaction.target());
                } else if (Files.exists(transaction.backup(), LinkOption.NOFOLLOW_LINKS)) {
                    restoreCopyBackup(transaction);
                }
            }
            if (Files.exists(transaction.target(), LinkOption.NOFOLLOW_LINKS)) {
                writeCopyMarker(transaction, "published");
                cleanupPublishedCopyStrict(transaction);
            } else {
                deleteTree(transaction.staging());
                Files.deleteIfExists(transaction.marker());
                forceDirectory(transaction.ownerRoot());
            }
            return;
        }

        if ("published".equals(transaction.state())) {
            if (!Files.exists(transaction.target(), LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(transaction.staging(), LinkOption.NOFOLLOW_LINKS)) {
                    createParent(transaction.target());
                    moveIntoPlace(transaction.staging(), transaction.target(), true);
                    forcePublishedPath(transaction.target());
                } else if (Files.exists(transaction.backup(), LinkOption.NOFOLLOW_LINKS)) {
                    restoreCopyBackup(transaction);
                }
            }
            if (!Files.exists(transaction.target(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("复制事务缺少目标和可恢复 backup: " + transaction.marker());
            }
            cleanupPublishedCopyStrict(transaction);
            return;
        }

        throw new IOException("未知复制事务状态: " + transaction.marker());
    }

    /**
     * 从文件名和 JSON marker 中恢复一条复制事务，并重新校验目标路径边界。
     * @param owner owner 根目录。
     * @param marker 复制事务 marker。
     * @return 已校验的复制事务。
     * @throws IOException marker 格式损坏、目标越界或 marker 是符号链接时抛出。
     */
    private CopyTransaction readCopyTransaction(Path owner, Path marker) throws IOException {
        if (Files.isSymbolicLink(marker)) {
            throw new IOException("复制事务 marker 不能是符号链接: " + marker);
        }
        String name = marker.getFileName().toString();
        String transactionId = name.substring(COPY_PREFIX.length(), name.length() - COPY_MARKER_SUFFIX.length());
        try {
            UUID.fromString(transactionId);
        } catch (IllegalArgumentException error) {
            throw new IOException("非法复制事务 ID: " + marker, error);
        }
        String json = Files.readString(marker, StandardCharsets.UTF_8);
        String state = copyMarkerValue(json, "state");
        String targetEncoded = copyMarkerValue(json, "target");
        String targetRelative;
        try {
            targetRelative = new String(Base64.getUrlDecoder().decode(targetEncoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw new IOException("非法复制事务目标: " + marker, error);
        }
        Path target = owner.resolve(targetRelative).normalize();
        if (targetRelative.isBlank() || !target.startsWith(owner) || target.equals(owner)
                || hasInternalComponent(targetRelative)) {
            throw new IOException("复制事务目标越界或属于内部路径: " + marker);
        }
        return new CopyTransaction(
                owner,
                marker,
                owner.resolve(COPY_PREFIX + transactionId + ".staging"),
                owner.resolve(COPY_OLD_PREFIX + transactionId),
                target,
                state
        );
    }

    /**
     * 以原子替换方式写入复制 marker；目标路径使用 URL-safe Base64，避免 JSON 转义改变路径。
     * @param transaction 复制事务。
     * @param state prepared、backup_moved 或 published。
     * @throws IOException marker 写入、fsync 或替换失败时抛出。
     */
    private void writeCopyMarker(CopyTransaction transaction, String state) throws IOException {
        String targetRelative = transaction.ownerRoot().relativize(transaction.target()).toString().replace('\\', '/');
        String encodedTarget = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(targetRelative.getBytes(StandardCharsets.UTF_8));
        String json = "{\"state\":\"" + state + "\",\"target\":\"" + encodedTarget + "\"}\n";
        Path temporary = transaction.marker().resolveSibling(transaction.marker().getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        Files.writeString(temporary, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        forceFile(temporary);
        moveIntoPlace(temporary, transaction.marker(), false);
        forceDirectory(transaction.ownerRoot());
    }

    /**
     * 读取本服务自己写入的简单 JSON 字符串字段。
     * @param json marker JSON 文本。
     * @param key 字段名。
     * @return 字段值。
     * @throws IOException 字段缺失或不是字符串时抛出。
     */
    private String copyMarkerValue(String json, String key) throws IOException {
        String prefix = "\"" + key + "\":\"";
        int start = json.indexOf(prefix);
        if (start < 0) throw new IOException("复制事务 marker 缺少字段: " + key);
        start += prefix.length();
        int end = json.indexOf('"', start);
        if (end < 0) throw new IOException("复制事务 marker 字段未闭合: " + key);
        return json.substring(start, end);
    }

    /**
     * 判断文件名是否为本服务生成的复制 marker。
     * @param name 文件名。
     * @return 是复制 marker 时为 true。
     */
    private boolean isCopyMarker(String name) {
        return name.startsWith(COPY_PREFIX) && name.endsWith(COPY_MARKER_SUFFIX);
    }

    /**
     * 复制尚未提交时恢复旧目标并删除 staging；任何无法证明安全的冲突都会保留 marker。
     * @param transaction 待回滚事务。
     * @throws IOException 回滚失败或新旧目标同时存在时抛出。
     */
    private void rollbackCopyTransaction(CopyTransaction transaction) throws IOException {
        if (Files.exists(transaction.backup(), LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(transaction.target(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("复制回滚时目标与 backup 同时存在: " + transaction.marker());
            }
            restoreCopyBackup(transaction);
        }
        deleteTree(transaction.staging());
        if (!Files.exists(transaction.backup(), LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(transaction.marker());
            forceDirectory(transaction.ownerRoot());
        }
    }

    /**
     * 数据库未提交时删除已经发布的新副本，并把覆盖前隐藏的旧目标恢复到原路径。
     * @param transaction 已发生可见性提交的复制事务。
     * @throws IOException 新目标删除、backup 恢复或 artifact 清理失败时抛出。
     */
    private void rollbackPublishedCopy(CopyTransaction transaction) throws IOException {
        validateCopyArtifact(transaction.staging());
        validateCopyArtifact(transaction.backup());
        if (Files.exists(transaction.target(), LinkOption.NOFOLLOW_LINKS)) {
            rejectSpecial(transaction.target());
            deleteTree(transaction.target());
            forceDirectory(transaction.target().getParent());
        }
        if (Files.exists(transaction.backup(), LinkOption.NOFOLLOW_LINKS)) {
            restoreCopyBackup(transaction);
        }
        deleteTree(transaction.staging());
        Files.deleteIfExists(transaction.marker());
        forceDirectory(transaction.ownerRoot());
    }

    /**
     * 恢复已移动到 backup 的旧目标。
     * @param transaction 复制事务。
     * @throws IOException backup 不是受控 artifact、目标已经存在或移动失败时抛出。
     */
    private void restoreCopyBackup(CopyTransaction transaction) throws IOException {
        validateCopyArtifact(transaction.backup());
        if (Files.exists(transaction.target(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("复制恢复目标已存在: " + transaction.target());
        }
        createParent(transaction.target());
        moveIntoPlace(transaction.backup(), transaction.target(), true);
        forcePublishedPath(transaction.target());
    }

    /**
     * 清理已发布复制的 staging、backup 和 marker；失败时保留剩余 artifact 供下次恢复。
     * @param transaction 已发布复制事务。
     */
    private void cleanupPublishedCopy(CopyTransaction transaction) {
        try {
            cleanupPublishedCopyStrict(transaction);
        } catch (IOException error) {
            LOGGER.warn("复制已成功但隐藏 artifact 清理失败，将由下次启动恢复: {}", transaction.marker(), error);
        }
    }

    /**
     * 严格清理一次已发布复制事务。
     * @param transaction 已发布复制事务。
     * @throws IOException artifact 清理或目录 fsync 失败时抛出。
     */
    private void cleanupPublishedCopyStrict(CopyTransaction transaction) throws IOException {
        validateCopyArtifact(transaction.staging());
        validateCopyArtifact(transaction.backup());
        deleteTree(transaction.staging());
        deleteTree(transaction.backup());
        Files.deleteIfExists(transaction.marker());
        forceDirectory(transaction.ownerRoot());
    }

    /**
     * 校验复制事务 artifact 不是符号链接，防止恢复逻辑把外部路径当作 backup/staging。
     * @param artifact 待校验 artifact。
     * @throws IOException artifact 是符号链接时抛出。
     */
    private void validateCopyArtifact(Path artifact) throws IOException {
        if (Files.isSymbolicLink(artifact)) {
            throw new IOException("复制事务 artifact 不能是符号链接: " + artifact);
        }
    }

    /**
     * 对已发布路径尽力完成 fsync；可见性提交已经发生时，fsync 失败不能撤销新内容。
     * @param path 已发布的文件或目录。
     */
    private void forcePublishedPath(Path path) {
        try {
            forcePath(path);
            forceDirectory(path.getParent());
        } catch (IOException error) {
            LOGGER.warn("复制恢复已发布内容但无法完成 fsync: {}", path, error);
        }
    }

    /**
     * 对普通文件或目录执行持久化；目录 fsync 在不支持该操作的 Windows provider 上降级为 best effort。
     * @param path 待持久化路径。
     * @throws IOException 文件 fsync 或目录 provider 以外的错误。
     */
    private void forcePath(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            forceDirectory(path);
        } else {
            forceFile(path);
        }
    }

    /**
     * 强制刷入普通文件内容。
     * @param file 普通文件。
     * @throws IOException 打开或刷入文件失败时抛出。
     */
    private void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    /**
     * 尽力刷入目录项；Java Windows provider 通常不允许打开目录作为 FileChannel。
     * @param directory 待刷入目录。
     * @throws IOException 当前 provider 返回非“目录 fsync 不支持”的错误时抛出。
     */
    private void forceDirectory(Path directory) throws IOException {
        if (directory == null) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (FileSystemException | UnsupportedOperationException ignored) {
            // Directory fsync is not exposed by the default Windows provider.
        }
    }

    /**
     * 递归复制普通文件或目录树到空 staging。
     * <p>每个源节点都拒绝符号链接和特殊文件；文件复制后立即 fsync，目录在子项完成后刷入目录项。</p>
     * @param source 已通过安全检查的源路径。
     * @param target 空 staging 目标路径。
     * @throws IOException 创建目录、复制或 fsync 任一节点失败时抛出。
     */
    private void copyTree(Path source, Path target) throws IOException {
        rejectSpecial(source);
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            createParent(target);
            Files.createDirectory(target);
            try (Stream<Path> paths = Files.list(source)) {
                for (Path child : paths.sorted().toList()) {
                    copyTree(child, target.resolve(child.getFileName()));
                }
            }
            forceDirectory(target);
        } else {
            createParent(target);
            Files.copy(source, target);
            forceFile(target);
        }
    }

    /**
     * 递归删除文件或目录树，不跟随目录判断中的符号链接。
     * <p>调用方必须先通过 owner 或回收站路径边界检查；此方法只负责物理删除，不更新
     * metadata、dedupe 或 outbox。</p>
     * @param path 已通过安全检查的待删除路径。
     * @throws IOException 删除目录内容或节点失败时抛出。
     */
    private void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> children = Files.list(path)) {
                for (Path child : children.toList()) deleteTree(child);
            }
        }
        Files.deleteIfExists(path);
    }

    /**
     * 以 1 MiB 缓冲区流式计算文件的 MD5 和 SHA-256 摘要。
     * @param file 待读取的普通文件。
     * @return 同时包含小写 MD5 和 SHA-256 文本的摘要值对象。
     * @throws IOException 打开或读取文件失败时抛出。
     */
    private Digests digest(Path file) throws IOException {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    md5.update(buffer, 0, read);
                    sha256.update(buffer, 0, read);
                }
            }
            return new Digests(hex(md5.digest()), hex(sha256.digest()));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JDK digest algorithm missing", error);
        }
    }

    /**
     * 校验一条 owner-scoped dedupe 命中是否仍可复用。
     * <p>同时检查 metadata、owner 安全路径、普通文件类型和 metadata revision；该方法只返回
     * 校验结果，不清理失效 dedupe 行。</p>
     * @param ownerId dedupe 所属 owner 的 UUID。
     * @param hit Mapper 返回的 dedupe 行，需包含 {@code path} 和 {@code file_revision}。
     * @return 命中仍指向当前普通文件和 revision 时为 {@code true}。
     */
    private boolean validDedupe(UUID ownerId, Map<String, Object> hit) {
        String path = String.valueOf(hit.get("path"));
        Map<String, Object> metadata = mapper.selectByPath(ownerId.toString(), path);
        if (metadata == null) return false;
        Path candidate;
        try {
            candidate = safePath(ownerId, path, false);
        } catch (FileStorageException error) {
            return false;
        }
        return Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                && sameLong(metadata.get("revision"), hit.get("file_revision"));
    }

    /**
     * 规范化并校验 MD5 文本。
     * <p>结果统一为小写；空值在 optional 模式下变为空字符串，在 required 模式下报错；
     * 非空值必须恰好是 32 位十六进制字符串。</p>
     * @param value 外部或客户端提供的 MD5 文本。
     * @param required 是否要求非空。
     * @return 小写 MD5，或 optional 模式下的空字符串。
     */
    private static String validateMd5(String value, boolean required) {
        String md5 = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (required && md5.isBlank()) throw new FileStorageException(400, "md5 不能为空");
        if (!md5.isBlank() && !md5.matches("[0-9a-f]{32}")) {
            throw new FileStorageException(400, "md5 必须是 32 位十六进制字符串");
        }
        return md5;
    }

    /**
     * 把字节数组编码为小写十六进制文本。
     * @param bytes 待编码的摘要字节。
     * @return 每个字节对应两个小写十六进制字符的字符串。
     */
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value));
        return result.toString();
    }

    /**
     * 比较两个可由 {@link #longValue(Object)} 读取的值是否代表同一个 long。
     * @param left 第一个数据库或内存值。
     * @param right 第二个数据库或内存值。
     * @return 两个值转换后的 long 相等时为 {@code true}。
     */
    private static boolean sameLong(Object left, Object right) {
        return longValue(left) == longValue(right);
    }

    /**
     * 把数据库返回的 Number 或数字文本转换为 long。
     * @param value Number 或可解析为 long 的值。
     * @return 转换后的 long。
     * @throws NumberFormatException value 不是数字或为 null 语义的文本时抛出。
     */
    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    /**
     * 按交替的 key/value 参数构造保持插入顺序的结果 Map。
     * @param values 偶数个 key/value 对；key 会转换为字符串。
     * @return 按传入顺序保存的 Map。
     * @throws ArrayIndexOutOfBoundsException values 个数为奇数时抛出。
     */
    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    /**
     * 保存一次复制事务的 owner 根、隐藏 artifact、目标和恢复状态。
     * @param ownerRoot owner 文件根目录。
     * @param marker durable 事务 marker。
     * @param staging 完整构建的隐藏 staging。
     * @param backup 覆盖前保存的旧目标。
     * @param target 对外可见目标。
     * @param state marker 状态。
     */
    private record CopyTransaction(Path ownerRoot, Path marker, Path staging, Path backup,
                                   Path target, String state) {
    }

    /**
     * 保存一次上传内容的 MD5 和 SHA-256 小写摘要。
     * @param md5 内容 MD5。
     * @param sha256 内容 SHA-256。
     */
    private record Digests(String md5, String sha256) {
    }

    /** 文件列表中已读取的物理状态快照。 */
    private record ListedEntry(Path path, String relativePath, boolean directory,
                               long size, double modifiedAt) {
    }
}
