package com.agentdrive.infrastructure.persistence;

import com.agentdrive.tasks.TaskStore;
import com.agentdrive.tasks.TaskWorkerStore;
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
class MybatisTaskWorkerStoreIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TaskStore tasks;

    @Autowired
    private TaskWorkerStore workers;

    @Test
    void claimsHeartbeatsAndCompletesLeasedTask() {
        String userId = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO users(id, username, password_hash) VALUES (?::uuid, ?, ?)",
                    userId, "worker-integration-" + userId, "test-hash");
            UUID owner = UUID.fromString(userId);
            TaskStore.EnqueueResult queued = tasks.enqueue(
                    owner, "integration.task", "integration", Map.of("value", 1),
                    "worker-dedupe-" + userId, "api", null
            );
            String taskId = String.valueOf(queued.task().get("id"));

            Map<String, Object> claimed = workers.claim("worker-1", "integration", 30);
            assertThat(claimed).containsEntry("id", taskId).containsEntry("status", "running");
            assertThat(workers.heartbeat("worker-1", taskId, 30)).isTrue();
            assertThat(workers.updateProgress("worker-1", taskId, 2, 5, "正在处理第 2 个文件", 30)).isTrue();
            assertThat(tasks.get(owner, UUID.fromString(taskId))).containsEntry("progress",
                    Map.of("current", 2, "total", 5, "message", "正在处理第 2 个文件"));
            assertThat(workers.succeed("worker-1", taskId, Map.of("ok", true))).isTrue();
            assertThat(tasks.get(owner, UUID.fromString(taskId))).containsEntry("status", "succeeded");
            assertThat(workers.heartbeat("worker-1", taskId, 30)).isFalse();
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?::uuid", userId);
        }
    }

    @Test
    void failedAttemptReleasesLeaseAndEntersDelayedRetryWait() {
        String userId = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO users(id, username, password_hash) VALUES (?::uuid, ?, ?)",
                    userId, "worker-retry-" + userId, "test-hash");
            UUID owner = UUID.fromString(userId);
            TaskStore.EnqueueResult queued = tasks.enqueue(
                    owner, "integration.retry", "integration-retry", Map.of(),
                    "worker-retry-dedupe-" + userId, "api", null
            );
            String taskId = String.valueOf(queued.task().get("id"));

            assertThat(workers.claim("worker-retry", "integration-retry", 30))
                    .containsEntry("id", taskId)
                    .containsEntry("attempts", 1);
            assertThat(workers.fail("worker-retry", taskId, "temporary provider failure")).isTrue();

            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT status, attempt, max_attempts, error, lease_owner, lease_until, finished_at,
                           available_at > now() AS delayed
                    FROM tasks
                    WHERE id = ?::uuid
                    """, taskId);
            assertThat(row).containsEntry("status", "retry_wait")
                    .containsEntry("attempt", 1)
                    .containsEntry("error", "temporary provider failure")
                    .containsEntry("delayed", true);
            assertThat(row.get("lease_owner")).isNull();
            assertThat(row.get("lease_until")).isNull();
            assertThat(row.get("finished_at")).isNull();
            assertThat(((Number) row.get("max_attempts")).intValue()).isGreaterThan(1);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM task_events WHERE task_id = ?::uuid AND event_type = 'failed'",
                    Integer.class, taskId)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?::uuid", userId);
        }
    }
}
