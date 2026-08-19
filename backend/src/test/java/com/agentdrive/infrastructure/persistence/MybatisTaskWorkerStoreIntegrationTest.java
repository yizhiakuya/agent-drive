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
            assertThat(workers.succeed("worker-1", taskId, Map.of("ok", true))).isTrue();
            assertThat(tasks.get(owner, UUID.fromString(taskId))).containsEntry("status", "succeeded");
            assertThat(workers.heartbeat("worker-1", taskId, 30)).isFalse();
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?::uuid", userId);
        }
    }
}
