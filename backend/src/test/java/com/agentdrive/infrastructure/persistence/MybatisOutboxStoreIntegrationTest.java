package com.agentdrive.infrastructure.persistence;

import com.agentdrive.outbox.OutboxStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "AGENT_DRIVE_JDBC_TEST_URL", matches = ".+")
class MybatisOutboxStoreIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxStore outbox;

    @Test
    void scopesIdempotentPendingEventsAndPublicationToOwner() {
        String userId = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO users(id, username, password_hash) VALUES (?::uuid, ?, ?)",
                    userId, "outbox-integration-" + userId, "test-hash");
            UUID owner = UUID.fromString(userId);
            String key = "outbox-key-" + userId;
            assertThat(outbox.enqueue(owner, "file.changed", "file", "file-1", key,
                    Map.of("path", "docs/a.txt"))).isTrue();
            assertThat(outbox.enqueue(owner, "file.changed", "file", "file-1", key,
                    Map.of("path", "docs/a.txt"))).isFalse();
            assertThat(outbox.pending(owner, 20)).hasSize(1);
            long id = ((Number) outbox.pending(owner, 20).get(0).get("id")).longValue();
            assertThat(outbox.markPublished(owner, id)).isTrue();
            assertThat(outbox.pending(owner, 20)).isEmpty();
            assertThat(outbox.markPublished(owner, id)).isFalse();

            String failedKey = key + "-failed";
            assertThat(outbox.enqueue(owner, "file.changed", "file", "file-2", failedKey,
                    Map.of("action", "upsert", "paths", java.util.List.of("docs/b.txt")))).isTrue();
            long failedId = ((Number) outbox.pending(owner, 20).get(0).get("id")).longValue();
            assertThat(outbox.recordFailure(failedId, "enqueue_failed: test", false)).isTrue();
            assertThat(outbox.pending(owner, 20)).singleElement().satisfies(event -> {
                assertThat(event).containsEntry("failure_count", 1);
                assertThat(event).containsEntry("last_error", "enqueue_failed: test");
            });
            assertThat(outbox.recordFailure(failedId, "invalid_payload_json", true)).isTrue();
            assertThat(outbox.pending(owner, 20)).isEmpty();
            assertThat(jdbc.queryForObject(
                    "SELECT dead_lettered_at IS NOT NULL FROM outbox_events WHERE id = ?", Boolean.class, failedId))
                    .isTrue();
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?::uuid", userId);
        }
    }
}
