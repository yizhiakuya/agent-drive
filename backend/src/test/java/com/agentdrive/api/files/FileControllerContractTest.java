package com.agentdrive.api.files;

import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.auth.AuthenticatedPrincipal;
import com.agentdrive.auth.CredentialAuthenticator;
import com.agentdrive.files.FileStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FileControllerContractTest {
    private Path media;
    private Path mediaDirectory;

    @AfterEach
    void cleanup() throws Exception {
        if (media != null) Files.deleteIfExists(media);
        if (mediaDirectory != null) Files.deleteIfExists(mediaDirectory);
    }

    @Test
    void listRequiresNormalAuthenticationEvenWhenQueryTokenIsPresent() {
        UUID owner = UUID.randomUUID();
        StubFiles files = new StubFiles(owner);
        WebTestClient client = client(owner, files);

        client.get()
                .uri("/api/v1/files?path=&token=device-token")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void mediaAcceptsDeviceQueryTokenAndDownloadSetsDisposition() throws Exception {
        UUID owner = UUID.randomUUID();
        StubFiles files = new StubFiles(owner);
        mediaDirectory = Files.createTempDirectory("agent-drive-file-contract-");
        media = mediaDirectory.resolve("hello.txt");
        Files.writeString(media, "hello");
        files.media = media;
        WebTestClient client = client(owner, files);

        client.get()
                .uri("/api/v1/files/raw?path=hello.txt&token=device-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("hello");

        client.get()
                .uri("/api/v1/files/download?path=hello.txt&token=device-token")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches("Content-Disposition", ".*attachment.*hello.txt.*");
    }

    @Test
    void authenticatedListPreservesPathAndItemsShape() {
        UUID owner = UUID.randomUUID();
        StubFiles files = new StubFiles(owner);
        WebTestClient client = client(owner, files);

        client.get()
                .uri("/api/v1/files?path=documents")
                .header("Authorization", "Bearer session-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.path").isEqualTo("documents")
                .jsonPath("$.items[0].name").isEqualTo("note.txt");
        assertThat(files.lastListPath).isEqualTo("documents");
    }

    @Test
    void authenticatedListPassesSemanticSearchMode() {
        UUID owner = UUID.randomUUID();
        StubFiles files = new StubFiles(owner);
        WebTestClient client = client(owner, files);

        client.get()
                .uri("/api/v1/files?path=documents&q=付款和验收&mode=semantic")
                .header("Authorization", "Bearer session-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].name").isEqualTo("note.txt");
        assertThat(files.lastListMode).isEqualTo("semantic");
    }

    private WebTestClient client(UUID owner, StubFiles files) {
        CredentialAuthenticator authenticator = credential -> {
            if ("session-token".equals(credential)) {
                return Optional.of(new AuthenticatedPrincipal(owner, AuthenticatedPrincipal.CredentialKind.SESSION));
            }
            if ("device-token".equals(credential)) {
                return Optional.of(new AuthenticatedPrincipal(owner, AuthenticatedPrincipal.CredentialKind.DEVICE));
            }
            return Optional.empty();
        };
        return WebTestClient.bindToController(new FileController(
                        files, new WebRequestPrincipalResolver(authenticator)))
                .controllerAdvice(new FileExceptionHandler())
                .build();
    }

    private static final class StubFiles implements FileStorageService {
        private final UUID owner;
        private String lastListPath;
        private String lastListMode;
        private Path media;

        private StubFiles(UUID owner) {
            this.owner = owner;
        }

        @Override
        public Map<String, Object> list(UUID userId, String path) {
            assertThat(userId).isEqualTo(owner);
            lastListPath = path;
            return Map.of("path", path, "items", java.util.List.of(Map.of(
                    "name", "note.txt", "path", path + "/note.txt", "is_dir", false, "size", 5, "mtime", 1.0
            )), "disk", Map.of("total", 10, "used", 5, "free", 5));
        }

        @Override
        public Map<String, Object> list(UUID userId, String path, String query, String mode) {
            lastListMode = mode;
            return list(userId, path);
        }

        @Override public Map<String, Object> info(UUID userId, String path) { return Map.of(); }
        @Override public Map<String, Object> dedupe(UUID userId, String md5) { return Map.of(); }
        @Override public Path fileForRead(UUID userId, String path) { return media; }
        @Override public Path createUploadTemp() { throw new UnsupportedOperationException(); }
        @Override public Map<String, Object> publishUpload(UUID userId, String directory, String filename, Path tempFile, String declaredMd5, boolean noclobber) { return Map.of(); }
        @Override public Map<String, Object> mkdir(UUID userId, String path) { return Map.of(); }
        @Override public Map<String, Object> rename(UUID userId, String source, String destination) { return Map.of(); }
        @Override public Map<String, Object> move(UUID userId, String source, String destinationDirectory, boolean overwrite) { return Map.of(); }
        @Override public Map<String, Object> copy(UUID userId, String source, String destination, boolean overwrite) { return Map.of(); }
        @Override public Map<String, Object> deleteToTrash(UUID userId, String path) { return Map.of(); }
        @Override public Map<String, Object> listTrash(UUID userId) { return Map.of("items", java.util.List.of()); }
        @Override public Map<String, Object> restoreTrash(UUID userId, String trashIdOrPath) { return Map.of(); }
        @Override public Map<String, Object> emptyTrash(UUID userId) { return Map.of("removed", 0); }
        @Override public void discardTemp(Path tempFile) { }
    }
}
