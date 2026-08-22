package com.agentdrive.infrastructure.persistence;

import com.agentdrive.infrastructure.persistence.mapper.ScheduleMapper;
import com.agentdrive.tasks.TaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MybatisScheduleStoreTest {
    @Test
    void normalizesTaskRoutingFieldsBeforePersistence() {
        ScheduleMapper mapper = mock(ScheduleMapper.class);
        TaskStore tasks = mock(TaskStore.class);
        MybatisScheduleStore schedules = new MybatisScheduleStore(mapper, new ObjectMapper(), tasks);
        UUID owner = UUID.randomUUID();

        schedules.upsert(owner, " trimmed ", null, "interval", "60",
                " index.cleanup ", " index ", Map.of(), true, -1, 0, "UTC");
        schedules.upsert(owner, "default-lane", null, "interval", "60",
                " index.cleanup ", " \t ", Map.of(), true, 0, 3, "UTC");

        verify(mapper).upsert(eq(owner.toString()), eq("trimmed"), eq("60"), eq("interval"), eq("60"),
                eq("index.cleanup"), eq("index"), eq("{}"), eq(true), eq(0), eq(1), eq("UTC"), anyDouble());
        verify(mapper).upsert(eq(owner.toString()), eq("default-lane"), eq("60"), eq("interval"), eq("60"),
                eq("index.cleanup"), eq("default"), eq("{}"), eq(true), eq(0), eq(3), eq("UTC"), anyDouble());
        verifyNoInteractions(tasks);
    }

    @Test
    void rejectsInvalidScheduleDefinitionsBeforePersistence() {
        ScheduleMapper mapper = mock(ScheduleMapper.class);
        TaskStore tasks = mock(TaskStore.class);
        MybatisScheduleStore schedules = new MybatisScheduleStore(mapper, new ObjectMapper(), tasks);
        UUID owner = UUID.randomUUID();

        assertThatIllegalArgumentException().isThrownBy(() -> schedules.upsert(
                owner, "bad-interval", null, "interval", "soon", "index.cleanup", "index",
                Map.of(), true, 0, 3, "UTC"));
        assertThatIllegalArgumentException().isThrownBy(() -> schedules.upsert(
                owner, "bad-daily", null, "daily", "25:00", "index.cleanup", "index",
                Map.of(), true, 0, 3, "UTC"));
        assertThatIllegalArgumentException().isThrownBy(() -> schedules.upsert(
                owner, "bad-zone", null, "daily", "03:30", "index.cleanup", "index",
                Map.of(), true, 0, 3, "Mars/Olympus"));
        assertThatIllegalArgumentException().isThrownBy(() -> schedules.upsert(
                owner, "bad-kind", null, "monthly", "1", "index.cleanup", "index",
                Map.of(), true, 0, 3, "UTC"));
        assertThatIllegalArgumentException().isThrownBy(() -> schedules.upsert(
                owner, "bad-cron", null, "cron", "not a cron", "index.cleanup", "index",
                Map.of(), true, 0, 3, "UTC"));

        verifyNoInteractions(mapper, tasks);
    }

    @Test
    void recordsManualRunWithoutChangingNextSchedule() {
        ScheduleMapper mapper = mock(ScheduleMapper.class);
        TaskStore tasks = mock(TaskStore.class);
        MybatisScheduleStore schedules = new MybatisScheduleStore(mapper, new ObjectMapper(), tasks);
        UUID owner = UUID.randomUUID();
        String taskId = UUID.randomUUID().toString();
        when(mapper.markManualRun(owner.toString(), "daily-report", taskId)).thenReturn(1);

        assertThat(schedules.markManualRun(owner, " daily-report ", taskId)).isTrue();
        verify(mapper).markManualRun(owner.toString(), "daily-report", taskId);
        verifyNoInteractions(tasks);
    }

    @Test
    void projectsLatestTaskFailureIntoScheduleErrorForAutomationCenter() {
        ScheduleMapper mapper = mock(ScheduleMapper.class);
        TaskStore tasks = mock(TaskStore.class);
        MybatisScheduleStore schedules = new MybatisScheduleStore(mapper, new ObjectMapper(), tasks);
        UUID owner = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Map<String, Object> row = new java.util.LinkedHashMap<>(schedule(
                owner, UUID.randomUUID().toString(), "daily-report", "daily", "09:00", 1_700_000_000L));
        row.put("last_task_id", taskId.toString());
        row.put("last_run_at", null);
        when(mapper.selectSchedules(owner.toString())).thenReturn(List.of(row));
        when(tasks.get(owner, taskId)).thenReturn(Map.of(
                "status", "failed", "error", "provider timeout", "created_at", 1_750_000_000L));

        Map<String, Object> listed = schedules.list(owner).get(0);

        assertThat(listed).containsEntry("last_error", "provider timeout")
                .containsEntry("last_run_at", 1_750_000_000L);
    }

    @Test
    void quarantinesLegacyInvalidScheduleAndDispatchesFollowingValidSchedule() {
        ScheduleMapper mapper = mock(ScheduleMapper.class);
        TaskStore tasks = mock(TaskStore.class);
        MybatisScheduleStore schedules = new MybatisScheduleStore(mapper, new ObjectMapper(), tasks);
        UUID owner = UUID.randomUUID();
        String invalidId = UUID.randomUUID().toString();
        String validId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        long dueAt = 1_700_000_000L;
        when(mapper.selectDueAll(20)).thenReturn(List.of(
                schedule(owner, invalidId, "broken", "daily", "99:30", dueAt),
                schedule(owner, validId, "healthy", "interval", "60", dueAt)
        ));
        when(tasks.enqueue(eq(owner), eq("index.cleanup"), eq("index"), eq(Map.of()),
                eq("schedule:" + validId + ":" + dueAt), eq("schedule"), eq(null), eq(7), eq(5)))
                .thenReturn(new TaskStore.EnqueueResult(Map.of("id", taskId), true));

        List<Map<String, Object>> result = schedules.dispatchDueAll(20);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsEntry("schedule", "broken")
                .containsEntry("disabled", true);
        assertThat(result.get(1)).containsEntry("schedule", "healthy")
                .containsEntry("queued", true);
        verify(mapper).disableInvalid(eq(owner.toString()), eq(invalidId), contains("daily schedule_value"));
        verify(tasks, never()).enqueue(eq(owner), eq("index.cleanup"), eq("index"), eq(Map.of()),
                eq("schedule:" + invalidId + ":" + dueAt), eq("schedule"), eq(null), eq(7), eq(5));
        verify(tasks).enqueue(eq(owner), eq("index.cleanup"), eq("index"), eq(Map.of()),
                eq("schedule:" + validId + ":" + dueAt), eq("schedule"), eq(null), eq(7), eq(5));
        verify(mapper).markDispatched(eq(owner.toString()), eq(validId), anyDouble(), eq(taskId));
    }

    @Test
    void calculatesRealNextRunsForFiveAndSixFieldCronExpressions() {
        long after = Instant.parse("2026-08-20T10:07:00Z").getEpochSecond();

        assertThat(MybatisScheduleStore.normalizeCronExpression("*/15 * * * *"))
                .isEqualTo("0 */15 * * * *");
        assertThat(MybatisScheduleStore.nextCronRun("0 */15 * * * *", "UTC", after))
                .isEqualTo(Instant.parse("2026-08-20T10:15:00Z").getEpochSecond());
        assertThat(MybatisScheduleStore.nextCronRun("30 8 * * * *", "Asia/Shanghai", after))
                .isEqualTo(Instant.parse("2026-08-20T10:08:30Z").getEpochSecond());
    }

    private static Map<String, Object> schedule(UUID owner, String id, String name, String kind,
                                                 String value, long nextRunAt) {
        return Map.ofEntries(
                Map.entry("id", id),
                Map.entry("owner_user_id", owner.toString()),
                Map.entry("name", name),
                Map.entry("schedule_kind", kind),
                Map.entry("schedule_value", value),
                Map.entry("timezone", "UTC"),
                Map.entry("task_type", "index.cleanup"),
                Map.entry("lane", "index"),
                Map.entry("payload_json", "{}"),
                Map.entry("priority", 7),
                Map.entry("max_attempts", 5),
                Map.entry("next_run_at", nextRunAt)
        );
    }
}
