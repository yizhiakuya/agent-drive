package com.agentdrive.infrastructure.persistence;

import com.agentdrive.tasks.ScheduleStore;
import com.agentdrive.tasks.TaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "AGENT_DRIVE_JDBC_TEST_URL", matches = ".+")
class MybatisScheduleStoreIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScheduleStore schedules;

    @Autowired
    private TaskStore tasks;

    @Test
    void upsertsListsAndDeletesOwnerSchedule() {
        String userId = UUID.randomUUID().toString();
        String otherUserId = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO users(id, username, password_hash) VALUES (?::uuid, ?, ?), (?::uuid, ?, ?)",
                    userId, "schedule-integration-" + userId, "test-hash",
                    otherUserId, "schedule-integration-" + otherUserId, "test-hash");
            UUID owner = UUID.fromString(userId);
            UUID other = UUID.fromString(otherUserId);
            Map<String, Object> saved = schedules.upsert(owner, "nightly", "30 3 * * *", "daily", "03:30",
                    "index.rebuild", "index", Map.of("prefix", "docs"), false, 4, 5, "Asia/Shanghai");
            assertThat(saved).containsEntry("name", "nightly").containsEntry("task_type", "index.rebuild");
            assertThat(schedules.list(owner)).hasSize(1);
            assertThat(schedules.list(other)).isEmpty();
            Map<String, Object> interval = schedules.upsert(owner, "every-second", "1", "interval", "1",
                    "index.file", "index", Map.of("path", "docs/a.txt"), true, 1, 2, "UTC");
            jdbc.update("UPDATE task_schedules SET next_run_at = to_timestamp(1) WHERE user_id = ?::uuid AND name = ?",
                    owner, "every-second");
            assertThat(schedules.dispatchDue(owner, 5)).singleElement().satisfies(item -> {
                assertThat(item).containsEntry("schedule", "every-second");
                assertThat(item).containsEntry("queued", true);
            });
            Number nextRun = jdbc.queryForObject(
                    "SELECT EXTRACT(EPOCH FROM next_run_at) FROM task_schedules WHERE user_id = ?::uuid AND name = ?",
                    Number.class, owner, "every-second");
            assertThat(nextRun).isNotNull();
            assertThat(nextRun.doubleValue()).isGreaterThan(System.currentTimeMillis() / 1000.0 - 5);
            assertThat(tasks.list(owner, java.util.List.of(), "index.file", false, 20, 0))
                    .singleElement().satisfies(task -> assertThat(task).containsEntry("origin", "schedule"));
            assertThatIllegalArgumentException().isThrownBy(() -> schedules.upsert(owner, "invalid", null,
                    "daily", "25:00", "index.cleanup", "index", Map.of(), true, 0, 3, "UTC"));
            jdbc.update("""
                    INSERT INTO task_schedules(user_id, name, cron, schedule_kind, schedule_value,
                                               task_kind, payload, enabled, next_run_at)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, '{}'::jsonb, true, to_timestamp(1))
                    """, owner, "legacy-invalid", "broken", "daily", "25:00", "index.cleanup");
            assertThat(schedules.dispatchDue(owner, 5)).singleElement().satisfies(item ->
                    assertThat(item).containsEntry("schedule", "legacy-invalid").containsEntry("disabled", true));
            assertThat(jdbc.queryForMap("""
                    SELECT enabled, last_error FROM task_schedules
                    WHERE user_id = ?::uuid AND name = 'legacy-invalid'
                    """, owner)).satisfies(row -> {
                assertThat(row).containsEntry("enabled", false);
                assertThat(row.get("last_error")).asString().contains("daily schedule_value");
            });
            assertThat(schedules.delete(owner, "nightly")).isTrue();
            assertThat(schedules.delete(owner, "every-second")).isTrue();
            assertThat(schedules.delete(owner, "legacy-invalid")).isTrue();
            assertThat(schedules.list(owner)).isEmpty();
            assertThat(schedules.delete(owner, "nightly")).isFalse();
        } finally {
            jdbc.update("DELETE FROM users WHERE id IN (?::uuid, ?::uuid)", userId, otherUserId);
        }
    }
}
