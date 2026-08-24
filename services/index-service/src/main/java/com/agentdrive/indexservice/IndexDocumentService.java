package com.agentdrive.indexservice;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Index Service 的文档写入、只读查询和迁移清单用例。
 *
 * <p>当前第一阶段只保存文本/视觉正文和 chunk 元数据，向量列预留给后续 pgvector
 * 迁移。所有表都带 owner_id/file_id/revision，不读取主 API 的索引表。</p>
 */
@Service
public class IndexDocumentService {
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
                       d.chunk_version, COUNT(c.id) AS chunk_count,
                       COALESCE(SUM(LENGTH(c.content)), 0) AS content_chars
                FROM index_documents d
                LEFT JOIN index_chunks c ON c.document_id = d.id
                WHERE d.owner_id = ?
                GROUP BY d.file_id, d.source_revision, d.document_type, d.extractor_version, d.chunk_version
                ORDER BY d.file_id, d.source_revision, d.document_type
                """, (rs, row) -> Map.of(
                "file_id", rs.getString("file_id"),
                "source_revision", rs.getLong("source_revision"),
                "document_type", rs.getString("document_type"),
                "extractor_version", rs.getString("extractor_version"),
                "chunk_version", rs.getString("chunk_version"),
                "chunk_count", rs.getInt("chunk_count"),
                "content_chars", rs.getLong("content_chars")), owner);
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
        String documentId = jdbc.query("""
                SELECT id FROM index_documents
                WHERE owner_id = ? AND file_id = ? AND source_revision = ? AND document_type = ?
                """, rs -> rs.next() ? rs.getString("id") : null,
                owner, file, request.sourceRevision(), request.documentType());
        if (documentId == null) {
            documentId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO index_documents(id, owner_id, file_id, source_revision,
                      document_type, extractor_version, content, chunk_version, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, documentId, owner, file, request.sourceRevision(), request.documentType(),
                    request.extractorVersion(), request.content(), request.chunkVersion());
        } else {
            jdbc.update("""
                    UPDATE index_documents SET extractor_version = ?, content = ?,
                      chunk_version = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND owner_id = ?
                    """, request.extractorVersion(), request.content(), request.chunkVersion(),
                    documentId, owner);
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
                "document_id", documentId, "chunk_count", request.chunks().size());
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
                                 String content, String chunkVersion, List<String> chunks) {
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
}
