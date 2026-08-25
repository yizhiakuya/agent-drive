package com.agentdrive.infrastructure.persistence;

import com.agentdrive.index.IndexStore;
import com.agentdrive.infrastructure.persistence.mapper.IndexMapper;
import com.agentdrive.index.RemoteIndexDocumentClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 通过 MyBatis 管理全文文档、文本 chunk 和向量 embedding 元数据。
 * <p>索引行始终按 owner 查询；替换文档在事务中删除旧版本 chunk 后写入新 chunk，
 * 向量查询可按文件路径过滤，清理和更新也保持数据库原子性。</p>
 */
public class MybatisIndexStore implements IndexStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(MybatisIndexStore.class);
    private final IndexMapper mapper;
    private final RemoteIndexDocumentClient remote;
    private final String readMode;

    /**
     * 保存索引 SQL Mapper。
     * @param mapper 读写文档、chunk 和 embedding 的 Mapper。
     */
    public MybatisIndexStore(IndexMapper mapper) {
        this(mapper, null, "local");
    }

    /** 创建带 Index Service 语义读路由的存储；local/dual/remote 可随时回退。 */
    public MybatisIndexStore(IndexMapper mapper, RemoteIndexDocumentClient remote, String readMode) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.remote = remote;
        String normalized = readMode == null ? "local" : readMode.trim().toLowerCase(java.util.Locale.ROOT);
        this.readMode = Set.of("local", "dual", "remote").contains(normalized) ? normalized : "local";
    }

    /**
     * 读取 owner 当前 revision 的全盘索引统计。
     * @param userId 文件归属 owner 的 UUID。
     * @param fingerprint 当前 embedding 指纹；为空时不计有效向量。
     * @return 规范化后的索引统计。
     */
    @Override
    public Stats statistics(UUID userId, String fingerprint) {
        requireUser(userId);
        Map<String, Object> row = mapper.selectStats(userId.toString(), fingerprint);
        if (row == null) return new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        return new Stats(
                intValue(row.get("eligible_files")),
                intValue(row.get("extracted_files")),
                intValue(row.get("vector_files")),
                intValue(row.get("non_vectorizable_files")),
                intValue(row.get("missing_vectors")),
                intValue(row.get("stale_vectors")),
                intValue(row.get("text_vector_files")),
                intValue(row.get("vision_vector_files")),
                intValue(row.get("text_missing_vectors")),
                intValue(row.get("vision_missing_vectors"))
        );
    }

    /**
     * 查询 owner 某个文件的索引元数据。
     * @param userId 文件所属 owner 的 UUID。
     * @param path owner 文件树中的相对路径。
     * @return 文件对应的索引行；没有匹配时由 Mapper 返回 {@code null}。
     */
    @Override
    public Map<String, Object> file(UUID userId, String path) {
        requireUser(userId);
        return mapper.selectFile(userId.toString(), path);
    }

    /**
     * 列出 owner 下用于索引的文件元数据。
     * @param userId 文件所属 owner 的 UUID。
     * @param prefix 可选路径前缀；空值表示全量。
     * @return 匹配前缀的文件索引行。
     */
    @Override
    public List<Map<String, Object>> files(UUID userId, String prefix) {
        requireUser(userId);
        return mapper.selectFiles(userId.toString(), prefix == null || prefix.isBlank() ? null : prefix);
    }

    /** 将索引概览的有限窗口直接下推到 PostgreSQL。 */
    @Override
    public List<Map<String, Object>> files(UUID userId, String prefix, int limit) {
        requireUser(userId);
        int bounded = Math.max(1, Math.min(limit, 1001));
        return mapper.selectFilesLimited(userId.toString(), prefix == null || prefix.isBlank() ? null : prefix,
                bounded);
    }

    /**
     * 执行 owner-scoped pgvector 语义搜索，并将返回上限限制在合理范围。
     *
     * @param userId 文件归属用户的 UUID。
     * @param fingerprint 当前 embedding 配置指纹。
     * @param vector 查询词向量文字。
     * @param prefix 可选的目录前缀。
     * @param limit 最大文件数。
     * @return 每个文件的最佳 chunk 匹配结果。
     */
    @Override
    public List<Map<String, Object>> semanticSearch(UUID userId, String fingerprint, String vector,
                                                     String prefix, int limit) {
        requireUser(userId);
        if (fingerprint == null || fingerprint.isBlank()) return List.of();
        if (vector == null || vector.isBlank()) throw new IllegalArgumentException("query vector is empty");
        List<Map<String, Object>> local = mapper.semanticSearch(userId.toString(), fingerprint, vector,
                prefix == null || prefix.isBlank() ? null : prefix,
                Math.max(1, Math.min(limit, 1000)));
        if (remote == null || "local".equals(readMode)) return local;
        try {
            List<Map<String, Object>> remoteRows = enrichRemote(userId, remote.searchSemantic(
                    userId, fingerprint, vector, prefix, Math.max(1, Math.min(limit, 1000))));
            if ("dual".equals(readMode)) {
                compareSemantic("semanticSearch", local, remoteRows);
                return local;
            }
            return remoteRows;
        } catch (RuntimeException error) {
            LOGGER.warn("remote index semantic read failed; using local fallback: {}", error.getMessage());
            return local;
        }
    }

    /**
     * 查询 owner-scoped 的多 chunk 证据窗口，并限制单次返回规模。
     */
    @Override
    public List<Map<String, Object>> semanticEvidence(UUID userId, String fingerprint, String vector,
                                                       String prefix, int limit, int neighbors,
                                                       Double minScore) {
        requireUser(userId);
        if (fingerprint == null || fingerprint.isBlank()) return List.of();
        if (vector == null || vector.isBlank()) throw new IllegalArgumentException("query vector is empty");
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        int boundedNeighbors = Math.max(0, Math.min(neighbors, 2));
        List<Map<String, Object>> local = mapper.semanticEvidence(userId.toString(), fingerprint, vector,
                prefix == null || prefix.isBlank() ? null : prefix,
                boundedLimit, boundedNeighbors, minScore);
        if (remote == null || "local".equals(readMode)) return local;
        try {
            List<Map<String, Object>> remoteRows = remote.searchSemantic(userId, fingerprint, vector, prefix,
                    boundedLimit).stream().map(row -> {
                        Map<String, Object> value = new java.util.LinkedHashMap<>(row);
                        value.put("match_chunk_index", row.get("chunk_index"));
                        value.put("match_content", row.get("search_snippet"));
                        value.put("result_rank", 0);
                        return value;
                    }).toList();
            if ("dual".equals(readMode)) {
                compareSemantic("semanticEvidence", local, remoteRows);
                return local;
            }
            return remoteRows;
        } catch (RuntimeException error) {
            LOGGER.warn("remote index semantic evidence read failed; using local fallback: {}", error.getMessage());
            return local;
        }
    }

    private List<Map<String, Object>> enrichRemote(UUID userId, List<Map<String, Object>> rows) {
        return rows.stream().map(row -> {
            Map<String, Object> value = new java.util.LinkedHashMap<>(row);
            String path = String.valueOf(row.getOrDefault("path", ""));
            Map<String, Object> file = mapper.selectFile(userId.toString(), path);
            if (file != null) {
                value.putIfAbsent("size", file.get("size_bytes"));
                value.putIfAbsent("mtime", file.get("mtime"));
            }
            return value;
        }).toList();
    }

    private void compareSemantic(String operation, List<Map<String, Object>> local,
                                 List<Map<String, Object>> remoteRows) {
        List<String> left = local.stream().map(row -> String.valueOf(row.get("file_id"))).toList();
        List<String> right = remoteRows.stream().map(row -> String.valueOf(row.get("file_id"))).toList();
        if (!left.equals(right)) {
            LOGGER.warn("index dual-read mismatch operation={} local_count={} remote_count={}",
                    operation, left.size(), right.size());
        }
    }

    /**
     * 在事务中替换某个文件版本的全文文档和 chunk。
     * @param userId 数据所属用户的唯一标识。
     * @param fileId 文件 UUID。
     * @param sourceRevision 文件内容 revision；向量有效性依赖它。
     * @param content 抽取后的全文；空值按空文本保存。
     * @param extractorVersion 生成全文的 extractor 版本。
     * @param chunks 按顺序切分出的文本 chunk。
     * @param chunkVersion chunk 算法版本。
     * @throws IllegalArgumentException file 不属于 owner 时抛出。
     */
    @Override
    @Transactional
    public void replaceDocument(UUID userId, UUID fileId, long sourceRevision, String documentType,
                                String content, String extractorVersion, List<String> chunks, String chunkVersion) {
        requireUser(userId);
        if (!IndexStore.TEXT_DOCUMENT_TYPE.equals(documentType)
                && !IndexStore.VISION_DOCUMENT_TYPE.equals(documentType)) {
            throw new IllegalArgumentException("unsupported document type");
        }
        Map<String, Object> document = mapper.upsertDocument(userId.toString(), fileId.toString(), sourceRevision,
                documentType, content == null ? "" : content, extractorVersion);
        if (document == null) throw new IllegalArgumentException("file is not owned by current user");
        String documentId = String.valueOf(document.get("id"));
        mapper.deleteOtherDocuments(userId.toString(), fileId.toString(), sourceRevision, documentType, extractorVersion);
        mapper.deleteChunks(documentId);
        for (int index = 0; index < chunks.size(); index++) {
            mapper.insertChunk(documentId, index, sourceRevision, chunkVersion, chunks.get(index));
        }
    }

    /**
     * 删除 owner 下不再对应当前文件/revision 的索引数据。
     * @param userId 文件所属 owner 的 UUID。
     * @return 删除的索引行数量。
     */
    @Override
    @Transactional
    public int cleanup(UUID userId) {
        requireUser(userId);
        return mapper.cleanup(userId.toString());
    }

    /** 清空 owner 全部向量，但保留可重新向量化的文本和视觉描述正文。 */
    @Override
    @Transactional
    public int clearEmbeddings(UUID userId) {
        requireUser(userId);
        return mapper.clearEmbeddings(userId.toString());
    }

    /**
     * 查询全部路径范围内、匹配 embedding fingerprint 的 chunk。
     * @param userId chunk 所属 owner 的 UUID。
     * @param fingerprint 当前 embedding 模型/配置指纹。
     * @param limit 最大返回数量，限制在 1 到 500。
     * @return 待向量检索或向量化的 chunk 列表。
     */
    @Override
    public List<Map<String, Object>> chunks(UUID userId, String fingerprint, int limit) {
        requireUser(userId);
        return chunks(userId, fingerprint, List.of(), limit);
    }

    /**
     * 查询匹配 fingerprint 且属于指定路径集合的 chunk。
     * @param userId chunk 所属 owner 的 UUID。
     * @param fingerprint 当前 embedding 模型/配置指纹。
     * @param paths 文件相对路径列表；空列表表示不限制路径。
     * @param limit 最大返回数量，限制在 1 到 500。
     * @return 路径过滤后的 chunk 列表。
     */
    @Override
    public List<Map<String, Object>> chunks(UUID userId, String fingerprint, List<String> paths, int limit) {
        requireUser(userId);
        return mapper.selectChunks(userId.toString(), fingerprint,
                paths == null ? List.of() : List.copyOf(paths), false, null,
                Math.max(1, Math.min(limit, 500)));
    }

    /**
     * 使用 UUID 游标读取强制重算批次；不预先删除任何已有向量。
     */
    @Override
    public List<Map<String, Object>> chunks(UUID userId, String fingerprint, List<String> paths,
                                             boolean includeCurrent, UUID afterChunkId, int limit) {
        requireUser(userId);
        return mapper.selectChunks(userId.toString(), fingerprint,
                paths == null ? List.of() : List.copyOf(paths), includeCurrent,
                afterChunkId == null ? null : afterChunkId.toString(), Math.max(1, Math.min(limit, 500)));
    }

    /**
     * 为单个 chunk 写入向量和 embedding fingerprint。
     * @param userId chunk 所属 owner 的 UUID。
     * @param chunkId chunk UUID。
     * @param vector 向量序列化文本。
     * @param fingerprint 生成该向量的模型配置指纹。
     * @return 实际更新的行数。
     */
    @Override
    @Transactional
    public int updateEmbedding(UUID userId, UUID chunkId, String vector, String fingerprint) {
        requireUser(userId);
        return mapper.updateEmbedding(userId.toString(), chunkId.toString(), vector, fingerprint);
    }

    /**
     * 校验索引操作的 owner 作用域。
     * @param userId 文件或 chunk 所属 owner 的 UUID。
     * @throws IllegalArgumentException userId 为空时抛出。
     */
    private static void requireUser(UUID userId) {
        if (userId == null) throw new IllegalArgumentException("userId must not be null");
    }

    /** 将 MyBatis 数字值安全转换为非负统计数。 */
    private static int intValue(Object value) {
        if (value == null) return 0;
        try {
            return Math.max(0, value instanceof Number number
                    ? number.intValue() : Integer.parseInt(String.valueOf(value)));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
