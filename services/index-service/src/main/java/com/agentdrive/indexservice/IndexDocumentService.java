package com.agentdrive.indexservice;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * Index Service 的文档写入、只读查询和迁移清单用例。
 *
 * <p>当前第一阶段只保存文本/视觉正文和 chunk 元数据，向量列预留给后续 pgvector
 * 迁移。所有表都带 owner_id/file_id/revision，不读取主 API 的索引表。</p>
 */
@Service
public class IndexDocumentService {
    private static final Pattern VECTOR_NUMBER = Pattern.compile("[-+]?([0-9]*\\.)?[0-9]+([eE][-+]?[0-9]+)?");
    private final JdbcTemplate jdbc;
    private final IndexServiceProperties properties;

    /** 创建索引服务。 */
    public IndexDocumentService(JdbcTemplate jdbc, IndexServiceProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** 返回数据库连接、令牌和 schema readiness。 */
    public Map<String, Object> ready() {
        try {
            Integer documents = jdbc.queryForObject("SELECT COUNT(*) FROM index_documents", Integer.class);
            return Map.of("ready", !properties.internalToken().isBlank(), "service", "index",
                    "documents", documents == null ? 0 : documents);
        } catch (RuntimeException error) {
            return Map.of("ready", false, "service", "index", "error", "database_unavailable");
        }
    }

    /** 返回当前 owner 的文档/chunk 迁移清单，不返回正文或向量。 */
    public Map<String, Object> manifest(String ownerId) {
        UUID owner = uuid(ownerId, "owner_id");
        List<Map<String, Object>> entries = jdbc.query("""
                SELECT d.file_id, d.source_revision, d.document_type, d.extractor_version,
                       d.chunk_version, d.file_path, COUNT(c.id) AS chunk_count,
                       COALESCE(SUM(LENGTH(c.content)), 0) AS content_chars
                FROM index_documents d
                LEFT JOIN index_chunks c ON c.document_id = d.id
                WHERE d.owner_id = ?
                GROUP BY d.file_id, d.source_revision, d.document_type, d.extractor_version, d.chunk_version, d.file_path
                ORDER BY d.file_id, d.source_revision, d.document_type
                """, (rs, row) -> {
                    Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("file_id", rs.getString("file_id"));
                    entry.put("source_revision", rs.getLong("source_revision"));
                    entry.put("document_type", rs.getString("document_type"));
                    entry.put("extractor_version", rs.getString("extractor_version"));
                    entry.put("chunk_version", rs.getString("chunk_version"));
                    entry.put("path", rs.getString("file_path"));
                    entry.put("chunk_count", rs.getInt("chunk_count"));
                    entry.put("content_chars", rs.getLong("content_chars"));
                    return entry;
                }, owner);
        return Map.of("ok", true, "owner_id", owner.toString(), "entries", entries,
                "document_count", entries.size());
    }

    /** 原子替换 owner 文件某个 revision/document_type 的正文和 chunks。 */
    @Transactional
    public Map<String, Object> replace(ReplaceRequest request) {
        UUID owner = uuid(request.ownerId(), "owner_id");
        UUID file = uuid(request.fileId(), "file_id");
        if (!List.of("text", "vision").contains(request.documentType())) {
            throw new IllegalArgumentException("document_type is invalid");
        }
        if (request.sourceRevision() < 1) {
            throw new IllegalArgumentException("source_revision is invalid");
        }
        if (request.chunks().size() > properties.maxChunksPerDocument()) {
            throw new IllegalArgumentException("too_many_chunks");
        }
        UUID documentId = jdbc.query("""
                SELECT id FROM index_documents
                WHERE owner_id = ? AND file_id = ? AND source_revision = ? AND document_type = ?
                """, rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                owner, file, request.sourceRevision(), request.documentType());
        if (documentId == null) {
            documentId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO index_documents(id, owner_id, file_id, source_revision,
                      document_type, extractor_version, content, chunk_version, file_path, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, documentId, owner, file, request.sourceRevision(), request.documentType(),
                    request.extractorVersion(), request.content(), request.chunkVersion(), cleanPath(request.path()));
        } else {
            jdbc.update("""
                    UPDATE index_documents SET extractor_version = ?, content = ?,
                      chunk_version = ?, file_path = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND owner_id = ?
                    """, request.extractorVersion(), request.content(), request.chunkVersion(),
                    cleanPath(request.path()), documentId, owner);
            jdbc.update("DELETE FROM index_chunks WHERE document_id = ? AND owner_id = ?", documentId, owner);
        }
        for (int i = 0; i < request.chunks().size(); i++) {
            jdbc.update("""
                    INSERT INTO index_chunks(id, owner_id, document_id, chunk_index,
                      source_revision, chunk_version, content, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, UUID.randomUUID(), owner, documentId, i, request.sourceRevision(),
                    request.chunkVersion(), request.chunks().get(i));
        }
        return Map.of("ok", true, "owner_id", owner.toString(), "file_id", file.toString(),
                "source_revision", request.sourceRevision(), "document_type", request.documentType(),
                "document_id", documentId.toString(), "chunk_count", request.chunks().size());
    }

    /** 按 owner/file/revision/type/chunk 约束写入远程 embedding，拒绝迟到结果污染新文档。 */
    @Transactional
    public Map<String, Object> updateEmbedding(EmbeddingRequest request) {
        UUID owner = uuid(request.ownerId(), "owner_id");
        UUID file = uuid(request.fileId(), "file_id");
        if (request.sourceRevision() < 1 || request.chunkIndex() < 0
                || request.embedding() == null || request.embedding().isBlank()) {
            throw new IllegalArgumentException("embedding metadata is invalid");
        }
        if (!List.of("text", "vision").contains(request.documentType())) {
            throw new IllegalArgumentException("document_type is invalid");
        }
        int updated = jdbc.update("""
                UPDATE index_chunks c SET embedding = ?, embedding_fingerprint = ?, updated_at = CURRENT_TIMESTAMP
                FROM index_documents d
                WHERE c.document_id = d.id AND c.owner_id = d.owner_id
                  AND d.owner_id = ? AND d.file_id = ? AND d.source_revision = ?
                  AND d.document_type = ? AND c.chunk_index = ? AND c.source_revision = d.source_revision
                """, request.embedding(), request.fingerprint(), owner, file, request.sourceRevision(),
                request.documentType(), request.chunkIndex());
        return Map.of("ok", true, "updated", updated, "owner_id", owner.toString(),
                "file_id", file.toString(), "chunk_index", request.chunkIndex());
    }

    /** 按 owner/file/revision/type 补齐路径元数据，找不到文档时返回稳定 0 更新结果。 */
    @Transactional
    public Map<String, Object> updatePath(String ownerId, String fileId, long sourceRevision,
                                          String documentType, String path) {
        UUID owner = uuid(ownerId, "owner_id");
        UUID file = uuid(fileId, "file_id");
        if (sourceRevision < 1 || !List.of("text", "vision").contains(documentType)
                || path == null || path.isBlank()) {
            throw new IllegalArgumentException("path metadata is invalid");
        }
        int updated = jdbc.update("""
                UPDATE index_documents SET file_path = ?, updated_at = CURRENT_TIMESTAMP
                WHERE owner_id = ? AND file_id = ? AND source_revision = ? AND document_type = ?
                """, cleanPath(path), owner, file, sourceRevision, documentType);
        return Map.of("ok", true, "updated", updated, "owner_id", owner.toString(),
                "file_id", file.toString(), "document_type", documentType);
    }

    /** 使用 cosine similarity 在服务自身索引库中返回每个文件的最佳 chunk。 */
    public Map<String, Object> semanticSearch(String ownerId, String fingerprint, String vector,
                                               String prefix, int limit) {
        UUID owner = uuid(ownerId, "owner_id");
        if (fingerprint == null || fingerprint.isBlank() || vector == null || vector.isBlank()) {
            throw new IllegalArgumentException("semantic search vector is invalid");
        }
        int bounded = Math.max(1, Math.min(limit, 1000));
        StringBuilder sql = new StringBuilder("""
                SELECT d.file_id, d.source_revision, d.document_type, d.file_path,
                       c.chunk_index, c.content, c.embedding
                FROM index_chunks c JOIN index_documents d ON d.id = c.document_id AND d.owner_id = c.owner_id
                WHERE c.owner_id = ? AND c.embedding_fingerprint = ? AND c.embedding IS NOT NULL
                """);
        List<Object> parameters = new ArrayList<>(List.of(owner, fingerprint));
        if (prefix != null && !prefix.isBlank()) {
            sql.append(" AND (d.file_path = ? OR d.file_path LIKE CONCAT(?, '/%'))");
            parameters.add(prefix);
            parameters.add(prefix);
        }
        List<ScoredChunk> candidates = jdbc.query(sql.toString(), (rs, row) -> scoreRow(
                        rs.getString("file_id"), rs.getLong("source_revision"),
                        rs.getString("document_type"), rs.getString("file_path"), rs.getInt("chunk_index"),
                        rs.getString("content"), rs.getString("embedding"), vector), parameters.toArray());
        Map<String, ScoredChunk> best = new java.util.LinkedHashMap<>();
        for (ScoredChunk candidate : candidates) {
            if (candidate.path() == null || candidate.path().isBlank()) continue;
            best.merge(candidate.fileId() + "|" + candidate.documentType(), candidate,
                    (left, right) -> left.score() >= right.score() ? left : right);
        }
        List<Map<String, Object>> items = best.values().stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
                        .thenComparing(ScoredChunk::path, Comparator.nullsLast(String::compareTo)))
                .limit(bounded)
                .map(ScoredChunk::asMap)
                .toList();
        return Map.of("ok", true, "mode", "semantic", "owner_id", owner.toString(), "items", items,
                "limit", bounded, "has_more", best.size() > bounded);
    }

    private ScoredChunk scoreRow(String fileId, long revision, String type, String path, int chunk,
                                 String content, String stored, String query) {
        double score = cosine(parseVector(stored), parseVector(query));
        return new ScoredChunk(fileId, revision, type, path, chunk,
                content == null ? "" : content.substring(0, Math.min(content.length(), 4000)), score);
    }

    private List<Double> parseVector(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.startsWith("[") && raw.endsWith("]")) raw = raw.substring(1, raw.length() - 1);
        if (raw.isBlank()) return List.of();
        List<Double> result = new ArrayList<>();
        for (String token : raw.split(",")) {
            String number = token.trim();
            if (!VECTOR_NUMBER.matcher(number).matches()) return List.of();
            try { result.add(Double.parseDouble(number)); } catch (NumberFormatException error) { return List.of(); }
        }
        return result;
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.isEmpty() || left.size() != right.size()) return -1.0;
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.size(); i++) {
            double a = left.get(i), b = right.get(i);
            dot += a * b; leftNorm += a * a; rightNorm += b * b;
        }
        return leftNorm == 0 || rightNorm == 0 ? -1.0 : dot / Math.sqrt(leftNorm * rightNorm);
    }

    private String cleanPath(String value) {
        return value == null || value.isBlank() ? null : value.trim().replace('\\', '/');
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    private record ScoredChunk(String fileId, long revision, String documentType, String path,
                               int chunkIndex, String snippet, double score) {
        Map<String, Object> asMap() {
            return Map.of("file_id", fileId, "source_revision", revision,
                    "vector_type", documentType, "path", path, "chunk_index", chunkIndex,
                    "search_snippet", snippet, "search_score", score);
        }
    }

    /** 在当前 owner 范围内执行受限文本检索，作为向量切换前的迁移校验路径。 */
    public Map<String, Object> search(String ownerId, String query, int limit) {
        UUID owner = uuid(ownerId, "owner_id");
        String text = query == null ? "" : query.trim();
        if (text.isBlank()) throw new IllegalArgumentException("query is required");
        int bounded = Math.max(1, Math.min(limit, 50));
        List<Map<String, Object>> items = jdbc.query("""
                SELECT d.file_id, d.source_revision, d.document_type, c.chunk_index, c.content
                FROM index_chunks c
                JOIN index_documents d ON d.id = c.document_id AND d.owner_id = c.owner_id
                WHERE c.owner_id = ? AND LOWER(c.content) LIKE LOWER(?)
                ORDER BY d.updated_at DESC, c.chunk_index
                LIMIT ?
                """, (rs, row) -> Map.of("file_id", rs.getString("file_id"),
                "source_revision", rs.getLong("source_revision"),
                "document_type", rs.getString("document_type"),
                "chunk_index", rs.getInt("chunk_index"),
                "content", rs.getString("content")), owner, "%" + text + "%", bounded);
        return Map.of("ok", true, "mode", "lexical_migration_check", "query", text,
                "items", items, "has_more", items.size() == bounded);
    }

    private UUID uuid(String value, String name) {
        try {
            return UUID.fromString(value == null ? "" : value.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(name + " is invalid", error);
        }
    }

    /** 文档替换请求。 */
    public record ReplaceRequest(String ownerId, String fileId, long sourceRevision,
                                 String documentType, String extractorVersion,
                                 String content, String chunkVersion, List<String> chunks, String path) {
        public ReplaceRequest(String ownerId, String fileId, long sourceRevision,
                              String documentType, String extractorVersion,
                              String content, String chunkVersion, List<String> chunks) {
            this(ownerId, fileId, sourceRevision, documentType, extractorVersion,
                    content, chunkVersion, chunks, null);
        }
        /** 固定空值和字段边界。 */
        public ReplaceRequest {
            content = content == null ? "" : content;
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
            if (extractorVersion == null || extractorVersion.isBlank()
                    || chunkVersion == null || chunkVersion.isBlank()) {
                throw new IllegalArgumentException("index versions are required");
            }
        }
    }

    /** 远程 embedding 更新请求。 */
    public record EmbeddingRequest(String ownerId, String fileId, long sourceRevision,
                                   String documentType, int chunkIndex, String embedding,
                                   String fingerprint) {
    }
}
