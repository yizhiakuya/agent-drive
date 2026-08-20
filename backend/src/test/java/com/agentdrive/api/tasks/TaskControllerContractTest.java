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

    @Test
    void returnsOwnerScopedTaskDetailsAndChildren() {
        UUID owner = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        StubTasks tasks = new StubTasks();
        tasks.detail = Map.of(
                "id", taskId.toString(),
                "type", "index.rebuild",
                "status", "failed",
                "payload", Map.of("force", true, "prefix", "docs/"),
                "result", Map.of("indexed", 8),
                "error", "embedding provider returned 502"
        );
        tasks.children = List.of(Map.of(
                "id", childId.toString(),
                "type", "index.file",
                "status", "failed",
                "error", "extractor timed out",
                "progress", Map.of("current", 1, "total", 2, "message", "处理中文档")
        ));

        client(owner, tasks).get().uri("/api/v1/tasks/{taskId}", taskId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.task.id").isEqualTo(taskId.toString())
                .jsonPath("$.task.payload.force").isEqualTo(true)
                .jsonPath("$.task.result.indexed").isEqualTo(8)
                .jsonPath("$.task.error").isEqualTo("embedding provider returned 502")
                .jsonPath("$.children[0].id").isEqualTo(childId.toString())
                .jsonPath("$.children[0].progress.message").isEqualTo("处理中文档");

        assertThat(tasks.detailOwner).isEqualTo(owner);
        assertThat(tasks.childrenOwner).isEqualTo(owner);
        assertThat(tasks.childrenParent).isEqualTo(taskId);
    }

    @Test
    void reportsWhetherTaskListHasAnotherPageWithoutReturningTheProbeRow() {
        UUID owner = UUID.randomUUID();
        StubTasks tasks = new StubTasks();
        tasks.listRows = List.of(
                Map.of("id", "task-1", "type", "index.file"),
                Map.of("id", "task-2", "type", "index.file")
        );

        client(owner, tasks).get().uri(uriBuilder -> uriBuilder
                        .path("/api/v1/tasks")
                        .queryParam("limit", 1)
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.items[0].id").isEqualTo("task-1")
                .jsonPath("$.has_more").isEqualTo(true);

        assertThat(tasks.listLimit).isEqualTo(2);
    }

    @Test
    void prunesOnlyOwnerHistoryWithTheServerRetentionPolicy() {
        UUID owner = UUID.randomUUID();
        StubTasks tasks = new StubTasks();
        tasks.pruneResult = Map.of(
                "jobs", 3,
                "events", 0,
                "workers", 0,
                "older_than_days", 30,
                "keep_recent", 2000
        );

        client(owner, tasks).post().uri("/api/v1/tasks/prune-history")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.removed").isEqualTo(3)
                .jsonPath("$.jobs").isEqualTo(3)
                .jsonPath("$.older_than_days").isEqualTo(30)
                .jsonPath("$.keep_recent").isEqualTo(2000);

        assertThat(tasks.pruneOwner).isEqualTo(owner);
        assertThat(tasks.pruneDays).isEqualTo(30);
        assertThat(tasks.pruneKeep).isEqualTo(2000);
    }

    @Test
    void clearsAllTerminalTasksWithoutUsingAutomaticRetentionPolicy() {
        UUID owner = UUID.randomUUID();
        StubTasks tasks = new StubTasks();
        tasks.clearTerminalRemoved = 4;

        client(owner, tasks).post().uri("/api/v1/tasks/clear-terminal")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.removed").isEqualTo(4)
                .jsonPath("$.jobs").isEqualTo(4);

        assertThat(tasks.clearTerminalOwner).isEqualTo(owner);
    }

    @Test
    void deletesOneTerminalTaskAndReturnsRemovedGroupCount() {
        UUID owner = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        StubTasks tasks = new StubTasks();
        tasks.deleteResult = new TaskStore.DeleteResult(true, 3, "deleted");

        client(owner, tasks).delete().uri("/api/v1/tasks/{taskId}", taskId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.task_id").isEqualTo(taskId.toString())
                .jsonPath("$.removed").isEqualTo(3)
                .jsonPath("$.jobs").isEqualTo(3);

        assertThat(tasks.deleteOwner).isEqualTo(owner);
        assertThat(tasks.deleteTaskId).isEqualTo(taskId);
    }

    @Test
    void rejectsDeletingTaskWithActiveChildren() {
        UUID owner = UUID.randomUUID();
        StubTasks tasks = new StubTasks();
        tasks.deleteResult = new TaskStore.DeleteResult(false, 0, "active_children");

        client(owner, tasks).delete().uri("/api/v1/tasks/{taskId}", UUID.randomUUID())
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void returnsNotFoundAndDoesNotReadChildrenForUnknownTask() {
        UUID owner = UUID.randomUUID();
        StubTasks tasks = new StubTasks();

        client(owner, tasks).get().uri("/api/v1/tasks/{taskId}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();

        assertThat(tasks.childrenOwner).isNull();
        assertThat(tasks.childrenParent).isNull();
    }

    @Test
    void distinguishesMissingAndNonRetryableTasks() {
        UUID owner = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        StubTasks missing = new StubTasks();

        client(owner, missing).post().uri("/api/v1/tasks/{taskId}/retry", taskId)
                .exchange()
                .expectStatus().isNotFound();

        StubTasks queued = new StubTasks();
        queued.retryResult = new TaskStore.TransitionResult(
                Map.of("id", taskId.toString(), "status", "queued"), false, "task_not_retryable");
        client(owner, queued).post().uri("/api/v1/tasks/{taskId}/retry", taskId)
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void treatsRepeatedCancelAsAnIdempotentSuccess() {
        UUID owner = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        StubTasks tasks = new StubTasks();
        tasks.cancelResult = new TaskStore.TransitionResult(
                Map.of("id", taskId.toString(), "status", "cancelled"), false, "task_not_active");

        client(owner, tasks).post().uri("/api/v1/tasks/{taskId}/cancel", taskId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.task.status").isEqualTo("cancelled")
                .jsonPath("$.changed").isEqualTo(false);
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
        private Map<String, Object> detail;
        private List<Map<String, Object>> children = List.of();
        private List<Map<String, Object>> listRows = List.of();
        private int listLimit;
        private UUID detailOwner;
        private UUID childrenOwner;
        private UUID childrenParent;
        private UUID pruneOwner;
        private int pruneDays;
        private int pruneKeep;
        private Map<String, Object> pruneResult = Map.of("jobs", 0, "events", 0, "workers", 0);
        private UUID clearTerminalOwner;
        private int clearTerminalRemoved;
        private UUID deleteOwner;
        private UUID deleteTaskId;
        private TaskStore.DeleteResult deleteResult;
        private TaskStore.TransitionResult cancelResult =
                new TaskStore.TransitionResult(null, false, "task_not_found");
        private TaskStore.TransitionResult retryResult =
                new TaskStore.TransitionResult(null, false, "task_not_found");

        @Override
        public List<Map<String, Object>> list(UUID userId, List<String> statuses, String type,
                                               boolean includeChildren, int limit, int offset) {
            this.listLimit = limit;
            return listRows;
        }

        @Override
        public Map<String, Object> overview(UUID userId) {
            return Map.of();
        }

        @Override
        public Map<String, Object> get(UUID userId, UUID taskId) {
            this.detailOwner = userId;
            return detail;
        }

        @Override
        public List<Map<String, Object>> childSummary(UUID userId, UUID parentId) {
            this.childrenOwner = userId;
            this.childrenParent = parentId;
            return children;
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
        public TransitionResult cancel(UUID userId, UUID taskId) {
            return cancelResult;
        }

        @Override
        public TransitionResult retry(UUID userId, UUID taskId) {
            return retryResult;
        }

        @Override
        public Map<String, Object> pruneHistory(UUID userId, int olderThanDays, int keepRecent) {
            this.pruneOwner = userId;
            this.pruneDays = olderThanDays;
            this.pruneKeep = keepRecent;
            return pruneResult;
        }

        @Override
        public int clearTerminal(UUID userId) {
            this.clearTerminalOwner = userId;
            return clearTerminalRemoved;
        }

        @Override
        public TaskStore.DeleteResult delete(UUID userId, UUID taskId) {
            this.deleteOwner = userId;
            this.deleteTaskId = taskId;
            return deleteResult;
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
