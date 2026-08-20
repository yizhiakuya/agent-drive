package com.agentdrive.infrastructure.persistence;

import com.agentdrive.files.FileStorageService;
import com.agentdrive.outbox.OutboxStore;
import com.agentdrive.infrastructure.persistence.mapper.FileMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("java-files")
@EnabledIfEnvironmentVariable(named = "AGENT_DRIVE_JDBC_TEST_URL", matches = ".+")
class MybatisFileOutboxIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FileMapper mapper;

    @Autowired
    private OutboxStore outbox;

    @Test
    void publishesOwnerScopedFileChangeAfterUpload() throws Exception {
        UUID owner = UUID.randomUUID();
        Path root = Files.createTempDirectory("agent-drive-file-outbox-");
        MybatisFileStorageService files = new MybatisFileStorageService(mapper, root, 10 * 1024 * 1024L, outbox);
        Path temp = null;
        try {
            jdbc.update("INSERT INTO users(id, username, password_hash) VALUES (?::uuid, ?, ?)",
                    owner, "file-outbox-" + owner, "test-hash");
            temp = files.createUploadTemp();
            Files.writeString(temp, "hello outbox");
            files.publishUpload(owner, "", "notes.txt", temp, "", false);

            List<Map<String, Object>> events = outbox.pending(owner, 10);
            assertThat(events).hasSize(1);
            assertThat(events.get(0).get("event_type")).isEqualTo("file.changed");
            assertThat(events.get(0).get("user_id")).isEqualTo(owner.toString());
            assertThat(events.get(0).get("payload")).isEqualTo(Map.of(
                    "action", "upsert", "paths", List.of("notes.txt")));
        } finally {
            if (temp != null) files.discardTemp(temp);
            jdbc.update("DELETE FROM users WHERE id = ?::uuid", owner);
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void writesUtf8TextAtomicallyAndPublishesChange() throws Exception {
        UUID owner = UUID.randomUUID();
        Path root = Files.createTempDirectory("agent-drive-file-text-");
        MybatisFileStorageService files = new MybatisFileStorageService(mapper, root, 10 * 1024 * 1024L, outbox);
        try {
            jdbc.update("INSERT INTO users(id, username, password_hash) VALUES (?::uuid, ?, ?)",
                    owner, "file-text-" + owner, "test-hash");
            Map<String, Object> result = files.writeText(owner, "Agent/notes/report.md", "你好\n报告", true);

            assertThat(result.get("uploaded")).isEqualTo(Map.of(
                    "path", "Agent/notes/report.md", "size", 13L));
            assertThat(Files.readString(files.fileForRead(owner, "Agent/notes/report.md"))).isEqualTo("你好\n报告");
            assertThat(outbox.pending(owner, 10)).anySatisfy(event -> {
                assertThat(event.get("event_type")).isEqualTo("file.changed");
                assertThat(event.get("payload")).isEqualTo(Map.of(
                        "action", "upsert", "paths", List.of("Agent/notes/report.md")));
            });
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?::uuid", owner);
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }
}
