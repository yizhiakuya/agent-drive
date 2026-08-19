package com.agentdrive.api.tasks;

import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.auth.AuthenticatedPrincipal;
import com.agentdrive.auth.CredentialAuthenticator;
import com.agentdrive.tasks.TaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaskControllerContractTest {
    @Test
    void queuesEmbeddingForAnOwnerScopedFileList() {
        UUID owner = UUID.randomUUID();
        StubTasks tasks = new StubTasks();
        WebTestClient client = client(owner, tasks);

        client.post().uri("/api/v1/tasks/embed-index")
                .bodyValue(Map.of("files", List.of("docs\\a.txt", "docs/a.txt", "notes.md")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.queued").isEqualTo(true)
                .jsonPath("$.task.type").isEqualTo("index.embed");

        assertThat(tasks.owner).isEqualTo(owner);
        assertThat(tasks.type).isEqualTo("index.embed");
        assertThat(tasks.payload).containsEntry("files", List.of("docs/a.txt", "notes.md"));
        assertThat(tasks.payload).containsEntry("force", false);
    }

    @Test
    void rejectsTraversalInEmbeddingFileList() {
        UUID owner = UUID.randomUUID();
        WebTestClient client = client(owner, new StubTasks());

        client.post().uri("/api/v1/tasks/embed-index")
                .bodyValue(Map.of("files", List.of("../secret.txt")))
                .exchange()
                .expectStatus().isBadRequest();
    }

    /** 验证视觉索引任务保留图片列表、force 标志和 owner 隔离信息。 */
    @Test
    void queuesVisionIndexForAnOwnerScopedImageList() {
        UUID owner = UUID.randomUUID();
        StubTasks tasks = new StubTasks();
        WebTestClient client = client(owner, tasks);

        client.post().uri("/api/v1/tasks/vision-index")
                .bodyValue(Map.of("files", List.of("photos\\a.jpg", "photos/a.jpg"), "force", true))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.queued").isEqualTo(true)
                .jsonPath("$.task.type").isEqualTo("index.vision");

        assertThat(tasks.owner).isEqualTo(owner);
        assertThat(tasks.type).isEqualTo("index.vision");
        assertThat(tasks.payload).containsEntry("files", List.of("photos/a.jpg"));
        assertThat(tasks.payload).containsEntry("force", true);
    }

    /** 验证视觉索引任务拒绝穿越 owner 存储根目录的路径。 */
    @Test
    void rejectsTraversalInVisionIndexFileList() {
        UUID owner = UUID.randomUUID();
        WebTestClient client = client(owner, new StubTasks());

        client.post().uri("/api/v1/tasks/vision-index")
                .bodyValue(Map.of("files", List.of("../secret.jpg")))
                .exchange()
                .expectStatus().isBadRequest();
    }

    private WebTestClient client(UUID owner, StubTasks tasks) {
        CredentialAuthenticator authenticator = credential ->
                "session-token".equals(credential)
                        ? Optional.of(new AuthenticatedPrincipal(owner, AuthenticatedPrincipal.CredentialKind.SESSION))
                        : Optional.empty();
        return WebTestClient.bindToController(new TaskController(
                        tasks, new WebRequestPrincipalResolver(authenticator)))
                .build()
                .mutate().defaultCookie("agentdrive_session", "session-token").build();
    }

    private static final class StubTasks implements TaskStore {
        private UUID owner;
        private String type;
        private Map<String, Object> payload;

        @Override
        public List<Map<String, Object>> list(UUID userId, List<String> statuses, String type,
                                              boolean includeChildren, int limit, int offset) {
            return List.of();
        }

        @Override
        public Map<String, Object> overview(UUID userId) {
            return Map.of();
        }

        @Override
        public Map<String, Object> get(UUID userId, UUID taskId) {
            return null;
        }

        @Override
        public List<Map<String, Object>> childSummary(UUID userId, UUID parentId) {
            return List.of();
        }

        @Override
        public EnqueueResult enqueue(UUID userId, String type, String lane, Map<String, Object> payload,
                                     String dedupeKey, String origin, UUID parentId) {
            this.owner = userId;
            this.type = type;
            this.payload = payload;
            return new EnqueueResult(Map.of("id", UUID.randomUUID().toString(), "type", type), true);
        }

        @Override
        public Map<String, Object> cancel(UUID userId, UUID taskId) {
            return null;
        }

        @Override
        public Map<String, Object> retry(UUID userId, UUID taskId) {
            return null;
        }

        @Override
        public long latestEventId(UUID userId) {
            return 0;
        }

        @Override
        public List<Map<String, Object>> events(UUID userId, long afterId, int limit) {
            return List.of();
        }
    }
}
