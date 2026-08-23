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
                .expectHeader().contentType("application/octet-stream")
                .expectHeader().valueMatches("Content-Disposition", ".*attachment.*hello.txt.*")
                .expectHeader().valueEquals("Cache-Control", "private, no-store")
                .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectBody(String.class).isEqualTo("hello");

        client.get()
                .uri("/api/v1/files/download?path=hello.txt&token=device-token")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches("Content-Disposition", ".*attachment.*hello.txt.*");
    }

    @Test
    void rawActiveContentIsForcedToDownloadWithoutSniffing() throws Exception {
        UUID owner = UUID.randomUUID();
        StubFiles files = new StubFiles(owner);
        mediaDirectory = Files.createTempDirectory("agent-drive-file-contract-");
        media = mediaDirectory.resolve("payload.html");
        Files.writeString(media, "<script>window.top.location='https://attacker.invalid'</script>");
        files.media = media;

        client(owner, files).get()
                .uri("/api/v1/files/raw?path=payload.html&token=device-token")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/octet-stream")
                .expectHeader().valueMatches("Content-Disposition", ".*attachment.*payload.html.*")
                .expectHeader().valueEquals("Content-Security-Policy", "sandbox; default-src 'none'")
                .expectHeader().valueEquals("Cross-Origin-Resource-Policy", "same-origin");
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

    @Test
    void authenticatedStatsPassesOwnerAndPath() {
        UUID owner = UUID.randomUUID();
        StubFiles files = new StubFiles(owner);

        client(owner, files).get()
                .uri("/api/v1/files/stats?path=相册同步")
                .header("Authorization", "Bearer session-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.path").isEqualTo("相册同步")
                .jsonPath("$.file_count").isEqualTo(777)
                .jsonPath("$.folder_count").isEqualTo(97)
                .jsonPath("$.complete").isEqualTo(true);
        assertThat(files.lastStatisticsPath).isEqualTo("相册同步");
    }

    @Test
    void authenticatedListPassesProductivityFiltersAndCollections() {
        UUID owner = UUID.randomUUID();
        StubFiles files = new StubFiles(owner);
        WebTestClient client = client(owner, files);

        client.get()
                .uri("/api/v1/files?path=documents&type=pdf&modified_after=10&modified_before=20&min_score=0.7&limit=25")
                .header("Authorization", "Bearer session-token")
                .exchange()
                .expectStatus().isOk();
        assertThat(files.lastListLimit).isEqualTo(25);
        assertThat(files.lastListType).isEqualTo("pdf");
        assertThat(files.lastMinScore).isEqualTo(0.7);
        assertThat(files.lastModifiedAfter).isEqualTo(10.0);
        assertThat(files.lastModifiedBefore).isEqualTo(20.0);

        client.get()
                .uri("/api/v1/files/favorites?limit=20")
                .header("Authorization", "Bearer session-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.mode").isEqualTo("favorites");
        assertThat(files.lastTrackingLimit).isEqualTo(20);

        client.get()
                .uri("/api/v1/files/recent?limit=15")
                .header("Authorization", "Bearer session-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.mode").isEqualTo("recent");
        assertThat(files.lastTrackingLimit).isEqualTo(15);

        client.post()
                .uri("/api/v1/files/favorites?path=documents/note.txt")
                .header("Authorization", "Bearer session-token")
                .exchange()
                .expectStatus().isOk();
        assertThat(files.favoritePath).isEqualTo("documents/note.txt");
        assertThat(files.favoriteValue).isTrue();

        client.delete()
                .uri("/api/v1/files/favorites?path=documents/note.txt")
                .header("Authorization", "Bearer session-token")
                .exchange()
                .expectStatus().isOk();
        assertThat(files.favoriteValue).isFalse();

        client.get()
                .uri("/api/v1/files/versions?path=documents/note.txt&limit=10")
                .header("Authorization", "Bearer session-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.path").isEqualTo("documents/note.txt");

        client.post()
                .uri("/api/v1/files/versions/restore?path=documents/note.txt&version_id=v1")
                .header("Authorization", "Bearer session-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.version_id").isEqualTo("v1");
        assertThat(files.versionPath).isEqualTo("documents/note.txt");
        assertThat(files.versionId).isEqualTo("v1");
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
        private int lastListLimit;
        private String lastListType;
        private Double lastMinScore;
        private Double lastModifiedAfter;
        private Double lastModifiedBefore;
        private String lastStatisticsPath;
        private int lastTrackingLimit;
        private String favoritePath;
        private boolean favoriteValue;
        private String versionPath;
        private String versionId;
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

        @Override
        public Map<String, Object> list(UUID userId, String path, String query, String mode,
                                        int limit, Double minScore, String type,
                                        Double modifiedAfter, Double modifiedBefore) {
            lastListLimit = limit;
            lastListType = type;
            lastMinScore = minScore;
            lastModifiedAfter = modifiedAfter;
            lastModifiedBefore = modifiedBefore;
            return list(userId, path, query, mode);
        }

        @Override
        public Map<String, Object> statistics(UUID userId, String path) {
            assertThat(userId).isEqualTo(owner);
            lastStatisticsPath = path;
            return Map.of(
                    "path", path,
                    "recursive", true,
                    "file_count", 777,
                    "folder_count", 97,
                    "total_size_bytes", 1234,
                    "complete", true,
                    "snapshot_at", "2026-08-23T04:00:00Z"
            );
        }

        @Override
        public Map<String, Object> listFavorites(UUID userId, int limit) {
            assertThat(userId).isEqualTo(owner);
            lastTrackingLimit = limit;
            return Map.of("mode", "favorites", "items", java.util.List.of(), "disk", Map.of());
        }

        @Override
        public Map<String, Object> listRecent(UUID userId, int limit) {
            assertThat(userId).isEqualTo(owner);
            lastTrackingLimit = limit;
            return Map.of("mode", "recent", "items", java.util.List.of(), "disk", Map.of());
        }

        @Override
        public Map<String, Object> setFavorite(UUID userId, String path, boolean favorite) {
            assertThat(userId).isEqualTo(owner);
            favoritePath = path;
            favoriteValue = favorite;
            return Map.of("path", path, "favorite", favorite);
        }

        @Override
        public Map<String, Object> listVersions(UUID userId, String path, int limit) {
            assertThat(userId).isEqualTo(owner);
            versionPath = path;
            return Map.of("path", path, "items", java.util.List.of(), "has_more", false);
        }

        @Override
        public Map<String, Object> restoreVersion(UUID userId, String path, String versionId) {
            assertThat(userId).isEqualTo(owner);
            versionPath = path;
            this.versionId = versionId;
            return Map.of("restored", Map.of("path", path, "size", 5), "version_id", versionId);
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
