package com.agentdrive.api;

import com.agentdrive.tasks.TaskWorkerStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@WebFluxTest(HealthController.class)
class HealthControllerTest {

    @TempDir
    Path tempDir;

    @Autowired
    private WebTestClient client;

    @Test
    void exposesCompatibleHealthContract() {
        client.get()
                .uri("/api/v1/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.ok").isEqualTo(true)
                .jsonPath("$.service").isEqualTo("agent-drive");
    }

    @Test
    void readinessRequiresDatabaseAndRecentWorkerHeartbeat() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskWorkerStore workers = mock(TaskWorkerStore.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(workers.onlineWorkerCount()).thenReturn(2);

        WebTestClient.bindToController(new HealthController(jdbc, workers)).build()
                .get().uri("/api/v1/ready")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.ready").isEqualTo(true)
                .jsonPath("$.database.ok").isEqualTo(true)
                .jsonPath("$.workers.ok").isEqualTo(true)
                .jsonPath("$.workers.online").isEqualTo(2)
                .jsonPath("$.workers.window_seconds").isEqualTo(10)
                .jsonPath("$.storage.ok").isEqualTo(true);
    }

    @Test
    void readinessReturns503WhenWorkerIsOffline() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskWorkerStore workers = mock(TaskWorkerStore.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(workers.onlineWorkerCount()).thenReturn(0);

        WebTestClient.bindToController(new HealthController(jdbc, workers)).build()
                .get().uri("/api/v1/ready")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.ready").isEqualTo(false)
                .jsonPath("$.database.ok").isEqualTo(true)
                .jsonPath("$.workers.ok").isEqualTo(false)
                .jsonPath("$.workers.online").isEqualTo(0)
                .jsonPath("$.storage.ok").isEqualTo(true);
    }

    @Test
    void readinessReturns503WhenDatabaseProbeFails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskWorkerStore workers = mock(TaskWorkerStore.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenThrow(new IllegalStateException("database down"));
        when(workers.onlineWorkerCount()).thenReturn(1);

        WebTestClient.bindToController(new HealthController(jdbc, workers)).build()
                .get().uri("/api/v1/ready")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.ready").isEqualTo(false)
                .jsonPath("$.database.ok").isEqualTo(false)
                .jsonPath("$.database.error").isEqualTo("database unavailable")
                .jsonPath("$.workers.ok").isEqualTo(true)
                .jsonPath("$.storage.ok").isEqualTo(true);
    }

    @Test
    void readinessReturns503WhenStorageDirectoryIsUnavailable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskWorkerStore workers = mock(TaskWorkerStore.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(workers.onlineWorkerCount()).thenReturn(1);

        WebTestClient.bindToController(new HealthController(jdbc, workers,
                        Path.of("target", "missing-readiness-storage")))
                .build()
                .get().uri("/api/v1/ready")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.ready").isEqualTo(false)
                .jsonPath("$.database.ok").isEqualTo(true)
                .jsonPath("$.workers.ok").isEqualTo(true)
                .jsonPath("$.storage.ok").isEqualTo(false)
                .jsonPath("$.storage.error").isEqualTo("storage directory unavailable");
    }

    @Test
    void readinessReportsLatestChecksumBackedBackupWithoutChangingReadyContract() throws Exception {
        Path data = Files.createDirectory(tempDir.resolve("data"));
        Path backups = Files.createDirectory(tempDir.resolve("backups"));
        Path archive = backups.resolve("agent-drive-java-20260821-000000.tar.gz");
        Files.writeString(archive, "backup");
        Files.writeString(backups.resolve(archive.getFileName() + ".sha256"), "checksum");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskWorkerStore workers = mock(TaskWorkerStore.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(workers.onlineWorkerCount()).thenReturn(1);

        WebTestClient.bindToController(new HealthController(jdbc, workers, data)).build()
                .get().uri("/api/v1/ready")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.ready").isEqualTo(true)
                .jsonPath("$.backup.ok").isEqualTo(true)
                .jsonPath("$.backup.retained").isEqualTo(1)
                .jsonPath("$.backup.last_backup_at").exists();
    }
}
