package com.agentdrive.api.tasks;

import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.auth.AuthenticatedPrincipal;
import com.agentdrive.auth.CredentialAuthenticator;
import com.agentdrive.tasks.ScheduleStore;
import com.agentdrive.tasks.TaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ScheduleControllerContractTest {
    @Test
    void rejectsMalformedDailyScheduleAsBadRequest() {
        UUID owner = UUID.randomUUID();
        ScheduleStore schedules = mock(ScheduleStore.class);
        when(schedules.upsert(any(), any(), any(), any(), any(), any(), any(), any(),
                any(Boolean.class), any(Integer.class), any(Integer.class), any()))
                .thenThrow(new IllegalArgumentException("daily schedule_value must use HH:mm"));

        client(owner, schedules).put().uri("/api/v1/schedules/nightly")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"scheduleKind":"daily","scheduleValue":"25:00","taskType":"index.cleanup",
                         "lane":"index","payload":{},"enabled":true,"priority":0,"maxAttempts":3,
                         "timezone":"UTC"}
                """)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void beanValidationRejectsMissingTaskTypeBeforeStoreCall() {
        UUID owner = UUID.randomUUID();
        ScheduleStore schedules = mock(ScheduleStore.class);

        client(owner, schedules).put().uri("/api/v1/schedules/nightly")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"scheduleKind":"daily","scheduleValue":"03:30","taskType":"",
                         "enabled":true,"priority":0,"maxAttempts":3,"timezone":"UTC"}
                        """)
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(schedules);
    }

    @Test
    void runEnqueuesOwnerScopedAutomationTaskWithoutExecutingInline() {
        UUID owner = UUID.randomUUID();
        ScheduleStore schedules = mock(ScheduleStore.class);
        TaskStore tasks = mock(TaskStore.class);
        when(schedules.list(owner)).thenReturn(java.util.List.of(java.util.Map.of(
                "name", "nightly",
                "payload", java.util.Map.of("rules", java.util.List.of("整理下载目录")),
                "priority", 4,
                "max_attempts", 5
        )));
        java.util.Map<String, Object> task = java.util.Map.of("id", "task-1", "type", "automation.run");
        when(tasks.enqueue(eq(owner), eq("automation.run"), eq("automation"), any(), any(),
                eq("api"), eq(null), anyInt(), anyInt()))
                .thenReturn(new TaskStore.EnqueueResult(task, true));

        WebTestClient.bindToController(new ScheduleController(
                        schedules, tasks, new WebRequestPrincipalResolver(credential ->
                                "session-token".equals(credential)
                                        ? Optional.of(new AuthenticatedPrincipal(owner,
                                        AuthenticatedPrincipal.CredentialKind.SESSION))
                                        : Optional.empty())))
                .build()
                .mutate().defaultCookie("agentdrive_session", "session-token").build()
                .post().uri("/api/v1/schedules/nightly/run")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.queued").isEqualTo(true)
                .jsonPath("$.schedule").isEqualTo("nightly")
                .jsonPath("$.task.type").isEqualTo("automation.run");

        verify(tasks).enqueue(eq(owner), eq("automation.run"), eq("automation"), any(), any(),
                eq("api"), eq(null), eq(4), eq(5));
    }

    @Test
    void runReturnsNotFoundForUnknownOwnerSchedule() {
        UUID owner = UUID.randomUUID();
        ScheduleStore schedules = mock(ScheduleStore.class);
        TaskStore tasks = mock(TaskStore.class);
        when(schedules.list(owner)).thenReturn(java.util.List.of());

        WebTestClient.bindToController(new ScheduleController(
                        schedules, tasks, new WebRequestPrincipalResolver(credential ->
                                "session-token".equals(credential)
                                        ? Optional.of(new AuthenticatedPrincipal(owner,
                                        AuthenticatedPrincipal.CredentialKind.SESSION))
                                        : Optional.empty())))
                .build()
                .mutate().defaultCookie("agentdrive_session", "session-token").build()
                .post().uri("/api/v1/schedules/missing/run")
                .exchange()
                .expectStatus().isNotFound();
    }

    private static WebTestClient client(UUID owner, ScheduleStore schedules) {
        CredentialAuthenticator authenticator = credential ->
                "session-token".equals(credential)
                        ? Optional.of(new AuthenticatedPrincipal(owner,
                        AuthenticatedPrincipal.CredentialKind.SESSION))
                        : Optional.empty();
        return WebTestClient.bindToController(new ScheduleController(
                        schedules, new WebRequestPrincipalResolver(authenticator)))
                .build()
                .mutate().defaultCookie("agentdrive_session", "session-token").build();
    }
}
