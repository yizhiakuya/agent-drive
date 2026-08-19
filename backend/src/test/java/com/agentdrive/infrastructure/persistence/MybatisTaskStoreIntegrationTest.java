package com.agentdrive.infrastructure.persistence;

import com.agentdrive.tasks.TaskStore;
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
class MybatisTaskStoreIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TaskStore tasks;

    @Test
    void persistsOwnerScopedDedupeStateTransitionsAndEvents() {
        String userId = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO users(id, username, password_hash) VALUES (?::uuid, ?, ?)",
                    userId, "task-integration-" + userId, "test-hash");
            UUID owner = UUID.fromString(userId);

            TaskStore.EnqueueResult first = tasks.enqueue(
                    owner, "index.rebuild", "index", Map.of("prefix", "docs", "force", true),
                    "integration-dedupe-" + userId, "api", null
            );
            TaskStore.EnqueueResult duplicate = tasks.enqueue(
                    owner, "index.rebuild", "index", Map.of("prefix", "docs"),
                    "integration-dedupe-" + userId, "api", null
            );
            assertThat(first.created()).isTrue();
            assertThat(duplicate.created()).isFalse();
            assertThat(duplicate.task().get("id")).isEqualTo(first.task().get("id"));
            UUID taskId = UUID.fromString(String.valueOf(first.task().get("id")));

            assertThat(tasks.list(owner, List.of("queued"), "index.rebuild", false, 50, 0))
                    .anySatisfy(task -> assertThat(task).containsEntry("id", taskId.toString()));
            assertThat(tasks.get(owner, taskId)).containsEntry("type", "index.rebuild");
            assertThat(((Map<?, ?>) tasks.overview(owner).get("counts")).get("queued")).isEqualTo(1L);
            assertThat(tasks.events(owner, 0, 50)).hasSize(2);

            Map<String, Object> cancelled = tasks.cancel(owner, taskId);
            assertThat(cancelled).containsEntry("status", "cancelled");
            Map<String, Object> retried = tasks.retry(owner, taskId);
            assertThat(retried).containsEntry("status", "queued");
            assertThat(tasks.latestEventId(owner)).isGreaterThan(2L);
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?::uuid", userId);
        }
    }

    @Test
    void prunesOldStandaloneHistoryButProtectsParentWithRecentChild() {
        String userId = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO users(id, username, password_hash) VALUES (?::uuid, ?, ?)",
                    userId, "task-prune-" + userId, "test-hash");
            UUID owner = UUID.fromString(userId);
            TaskStore.EnqueueResult oldStandalone = tasks.enqueue(
                    owner, "index.file", "index", Map.of("path", "old.txt"), null, "test", null);
            TaskStore.EnqueueResult oldParent = tasks.enqueue(
                    owner, "index.rebuild", "index", Map.of(), null, "test", null);
            UUID parentId = UUID.fromString(String.valueOf(oldParent.task().get("id")));
            TaskStore.EnqueueResult recentChild = tasks.enqueue(
                    owner, "index.file", "index", Map.of("path", "recent.txt"), null, "test", parentId);
            jdbc.update("UPDATE tasks SET status = 'succeeded', finished_at = now() - interval '31 days', updated_at = now() - interval '31 days' WHERE id = ?::uuid",
                    oldStandalone.task().get("id"));
            jdbc.update("UPDATE tasks SET status = 'succeeded', finished_at = now() - interval '31 days', updated_at = now() - interval '31 days' WHERE id = ?::uuid",
                    oldParent.task().get("id"));
            jdbc.update("UPDATE tasks SET status = 'succeeded', finished_at = now(), updated_at = now() WHERE id = ?::uuid",
                    recentChild.task().get("id"));

            Map<String, Object> result = tasks.pruneHistory(owner, 30, 1);

            assertThat(result).containsEntry("jobs", 1);
            assertThat(tasks.get(owner, UUID.fromString(String.valueOf(oldStandalone.task().get("id"))))).isNull();
            assertThat(tasks.get(owner, parentId)).isNotNull();
            assertThat(tasks.get(owner, UUID.fromString(String.valueOf(recentChild.task().get("id"))))).isNotNull();
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?::uuid", userId);
        }
    }
}
