package com.agentdrive.infrastructure.persistence;

import com.agentdrive.index.IndexStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "AGENT_DRIVE_JDBC_TEST_URL", matches = ".+")
class MybatisIndexStoreIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private IndexStore index;

    @Test
    void replacesChunksAndCleansStaleRevisionsPerOwner() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO users(id, username, password_hash) VALUES (?::uuid, ?, ?), (?::uuid, ?, ?)",
                    owner, "index-integration-" + owner, "test-hash",
                    other, "index-integration-" + other, "test-hash");
            jdbc.update("INSERT INTO files(id, user_id, path, is_dir, size_bytes, revision) VALUES (?::uuid, ?::uuid, ?, false, ?, 1)",
                    fileId, owner, "notes.txt", 12);
            index.replaceDocument(owner, fileId, 1, "hello world", "java-test", List.of("hello", "world"), "chunk-test");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE file_id = ?::uuid", Integer.class, fileId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM document_chunks WHERE document_id IN (SELECT id FROM documents WHERE file_id = ?::uuid)", Integer.class, fileId)).isEqualTo(2);
            List<Map<String, Object>> chunks = index.chunks(owner, null, 10);
            assertThat(chunks).hasSize(2);
            UUID chunkId = UUID.fromString(String.valueOf(chunks.get(0).get("id")));
            assertThat(index.updateEmbedding(owner, chunkId, "[0.1,0.2,0.3]", "embedding-test")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT embedding::text FROM document_chunks WHERE id = ?::uuid", String.class, chunkId))
                    .isEqualTo("[0.1,0.2,0.3]");
            assertThat(index.semanticSearch(owner, "embedding-test", "[0.1,0.2,0.3]", "", 10))
                    .singleElement()
                    .satisfies(row -> assertThat(row)
                            .containsEntry("path", "notes.txt")
                            .containsKey("search_snippet"));
            assertThat(index.chunks(owner, "embedding-test", List.of("notes.txt"), 10)).hasSize(1);
            assertThat(index.chunks(owner, "embedding-test", List.of("notes.txt"), true, null, 10))
                    .hasSize(2);
            assertThat(index.chunks(other, null, 10)).isEmpty();
            assertThat(index.file(other, "notes.txt")).isNull();
            jdbc.update("UPDATE files SET revision = 2 WHERE id = ?::uuid", fileId);
            assertThat(index.cleanup(owner)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM users WHERE id IN (?::uuid, ?::uuid)", owner, other);
        }
    }
}
