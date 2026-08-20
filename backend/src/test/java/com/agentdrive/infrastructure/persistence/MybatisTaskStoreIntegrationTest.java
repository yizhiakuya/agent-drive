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
    void activeDedupeKeysAreIsolatedByOwnerAndKeepExplicitSchedulingFields() {
        String firstUser = UUID.randomUUID().toString();
        String secondUser = UUID.randomUUID().toString();
        String dedupe = "shared-owner-scoped-key-" + UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO users(id, username, password_hash) VALUES (?::uuid, ?, ?), (?::uuid, ?, ?)",
                    firstUser, "task-owner-a-" + firstUser, "test-hash",
                    secondUser, "task-owner-b-" + secondUser, "test-hash");

            TaskStore.EnqueueResult first = tasks.enqueue(UUID.fromString(firstUser), "index.file", "index",
                    Map.of("path", "a.txt"), dedupe, "schedule", null, 9, 7);
            TaskStore.EnqueueResult second = tasks.enqueue(UUID.fromString(secondUser), "index.file", "index",
                    Map.of("path", "b.txt"), dedupe, "schedule", null, 4, 5);

            assertThat(first.created()).isTrue();
            assertThat(second.created()).isTrue();
            assertThat(first.task()).containsEntry("priority", 9).containsEntry("max_attempts", 7);
            assertThat(second.task()).containsEntry("priority", 4).containsEntry("max_attempts", 5);
        } finally {
            jdbc.update("DELETE FROM users WHERE id IN (?::uuid, ?::uuid)", firstUser, secondUser);
        }
    }

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

            TaskStore.TransitionResult cancelled = tasks.cancel(owner, taskId);
            assertThat(cancelled.changed()).isTrue();
            assertThat(cancelled.task()).containsEntry("status", "cancelled");
            long cancelEventId = tasks.latestEventId(owner);

            TaskStore.TransitionResult repeatedCancel = tasks.cancel(owner, taskId);
            assertThat(repeatedCancel.changed()).isFalse();
            assertThat(repeatedCancel.reason()).isEqualTo("task_not_active");
            assertThat(repeatedCancel.task().get("updated_at")).isEqualTo(cancelled.task().get("updated_at"));
            assertThat(tasks.latestEventId(owner)).isEqualTo(cancelEventId);

            TaskStore.TransitionResult retried = tasks.retry(owner, taskId);
            assertThat(retried.changed()).isTrue();
            assertThat(retried.task()).containsEntry("status", "queued");
            assertThat(tasks.latestEventId(owner)).isGreaterThan(cancelEventId);

            TaskStore.TransitionResult repeatedRetry = tasks.retry(owner, taskId);
            assertThat(repeatedRetry.changed()).isFalse();
            assertThat(repeatedRetry.reason()).isEqualTo("task_not_retryable");
            TaskStore.TransitionResult missingRetry = tasks.retry(owner, UUID.randomUUID());
            assertThat(missingRetry.task()).isNull();
            assertThat(missingRetry.reason()).isEqualTo("task_not_found");
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

    @Test
    void clearsAllTerminalTasksButKeepsActiveTasksAndProtectedParents() {
        String userId = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO users(id, username, password_hash) VALUES (?::uuid, ?, ?)",
                    userId, "task-clear-" + userId, "test-hash");
            UUID owner = UUID.fromString(userId);

            TaskStore.EnqueueResult terminalParent = tasks.enqueue(
                    owner, "index.rebuild", "index", Map.of(), null, "test", null);
            UUID terminalParentId = UUID.fromString(String.valueOf(terminalParent.task().get("id")));
            TaskStore.EnqueueResult terminalChild = tasks.enqueue(
                    owner, "index.file", "index", Map.of("path", "done.txt"), null, "test", terminalParentId);

            TaskStore.EnqueueResult activeRoot = tasks.enqueue(
                    owner, "index.rebuild", "index", Map.of(), null, "test", null);
            UUID activeRootId = UUID.fromString(String.valueOf(activeRoot.task().get("id")));

            TaskStore.EnqueueResult protectedParent = tasks.enqueue(
                    owner, "index.rebuild", "index", Map.of(), null, "test", null);
            UUID protectedParentId = UUID.fromString(String.valueOf(protectedParent.task().get("id")));
            TaskStore.EnqueueResult activeChild = tasks.enqueue(
                    owner, "index.file", "index", Map.of("path", "active.txt"), null, "test", protectedParentId);
            UUID activeChildId = UUID.fromString(String.valueOf(activeChild.task().get("id")));

            jdbc.update("UPDATE tasks SET status = 'succeeded', finished_at = now(), updated_at = now() WHERE id IN (?::uuid, ?::uuid)",
                    terminalParentId, terminalChild.task().get("id"));
            jdbc.update("UPDATE tasks SET status = 'running', updated_at = now() WHERE id = ?::uuid", activeRootId);
            jdbc.update("UPDATE tasks SET status = 'succeeded', finished_at = now(), updated_at = now() WHERE id = ?::uuid",
                    protectedParentId);
            jdbc.update("UPDATE tasks SET status = 'running', updated_at = now() WHERE id = ?::uuid", activeChildId);

            int removed = tasks.clearTerminal(owner);

            assertThat(removed).isEqualTo(2);
            assertThat(tasks.get(owner, terminalParentId)).isNull();
            assertThat(tasks.get(owner, UUID.fromString(String.valueOf(terminalChild.task().get("id"))))).isNull();
            assertThat(tasks.get(owner, activeRootId)).isNotNull();
            assertThat(tasks.get(owner, protectedParentId)).isNotNull();
            assertThat(tasks.get(owner, activeChildId)).isNotNull();
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?::uuid", userId);
        }
    }

    @Test
    void deletesTerminalTaskGroupAndRejectsParentWithActiveChild() {
        String userId = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO users(id, username, password_hash) VALUES (?::uuid, ?, ?)",
                    userId, "task-delete-" + userId, "test-hash");
            UUID owner = UUID.fromString(userId);

            TaskStore.EnqueueResult parent = tasks.enqueue(
                    owner, "index.rebuild", "index", Map.of(), null, "test", null);
            UUID parentId = UUID.fromString(String.valueOf(parent.task().get("id")));
            TaskStore.EnqueueResult child = tasks.enqueue(
                    owner, "index.file", "index", Map.of(), null, "test", parentId);
            UUID childId = UUID.fromString(String.valueOf(child.task().get("id")));
            jdbc.update("UPDATE tasks SET status = 'succeeded', finished_at = now(), updated_at = now() WHERE id IN (?::uuid, ?::uuid)",
                    parentId, childId);

            TaskStore.DeleteResult deleted = tasks.delete(owner, parentId);
            assertThat(deleted.deleted()).isTrue();
            assertThat(deleted.removed()).isEqualTo(2);
            assertThat(tasks.get(owner, parentId)).isNull();
            assertThat(tasks.get(owner, childId)).isNull();

            TaskStore.EnqueueResult protectedParent = tasks.enqueue(
                    owner, "index.rebuild", "index", Map.of(), null, "test", null);
            UUID protectedParentId = UUID.fromString(String.valueOf(protectedParent.task().get("id")));
            TaskStore.EnqueueResult activeChild = tasks.enqueue(
                    owner, "index.file", "index", Map.of(), null, "test", protectedParentId);
            UUID activeChildId = UUID.fromString(String.valueOf(activeChild.task().get("id")));
            jdbc.update("UPDATE tasks SET status = 'succeeded', finished_at = now(), updated_at = now() WHERE id = ?::uuid",
                    protectedParentId);
            jdbc.update("UPDATE tasks SET status = 'running', updated_at = now() WHERE id = ?::uuid", activeChildId);

            TaskStore.DeleteResult rejected = tasks.delete(owner, protectedParentId);
            assertThat(rejected.deleted()).isFalse();
            assertThat(rejected.reason()).isEqualTo("active_children");
            assertThat(tasks.get(owner, protectedParentId)).isNotNull();
            assertThat(tasks.get(owner, activeChildId)).isNotNull();
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?::uuid", userId);
        }
    }
}
