package com.agentdrive.api.tasks;

import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.auth.AuthenticatedPrincipal;
import com.agentdrive.auth.CredentialAuthenticator;
import com.agentdrive.tasks.ScheduleStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
