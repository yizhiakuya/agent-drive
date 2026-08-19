package com.agentdrive.api.automation;

import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.auth.AuthenticatedPrincipal;
import com.agentdrive.auth.CredentialAuthenticator;
import com.agentdrive.files.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutomationControllerContractTest {
    @Test
    void returnsLatestOwnerScopedAutomationReport() throws Exception {
        UUID owner = UUID.randomUUID();
        FileStorageService files = mock(FileStorageService.class);
        Path report = Files.createTempFile("agent-drive-automation-report-", ".md");
        Files.writeString(report, "automation result");
        try {
            when(files.list(owner, "Agent/notes")).thenReturn(Map.of(
                    "items", List.of(Map.of("name", "自动化报告-2026-08-18.md"))));
            when(files.fileForRead(owner, "Agent/notes/自动化报告-2026-08-18.md")).thenReturn(report);
            WebTestClient client = client(owner, files);

            client.get().uri("/api/v1/automation/latest")
                    .header("Authorization", "Bearer session-token")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.last_run").isEmpty()
                    .jsonPath("$.report.date").isEqualTo("2026-08-18")
                    .jsonPath("$.report.text").isEqualTo("automation result");
        } finally {
            Files.deleteIfExists(report);
        }
    }

    @Test
    void requiresAuthenticationForAutomationReport() {
        WebTestClient client = client(UUID.randomUUID(), mock(FileStorageService.class));
        client.get().uri("/api/v1/automation/latest").exchange()
                .expectStatus().isUnauthorized();
    }

    private WebTestClient client(UUID owner, FileStorageService files) {
        CredentialAuthenticator authenticator = credential -> "session-token".equals(credential)
                ? Optional.of(new AuthenticatedPrincipal(owner, AuthenticatedPrincipal.CredentialKind.SESSION))
                : Optional.empty();
        return WebTestClient.bindToController(new AutomationController(
                        new WebRequestPrincipalResolver(authenticator), files))
                .build();
    }
}
