package com.agentdrive.tasks;

import com.agentdrive.files.FileStorageService;
import com.agentdrive.index.IndexingService;
import com.agentdrive.index.EmbeddingService;
import com.agentdrive.progress.TaskProgressReporter;
import com.agentdrive.vision.VisionDescriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexTaskHandlerTest {
    private static <T> T mock(Class<T> type) {
        T value = org.mockito.Mockito.mock(type);
        if (value instanceof TaskWorkerStore workers) {
            when(workers.updateProgress(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyInt()))
                    .thenReturn(true);
            when(workers.succeed(anyString(), anyString(), any())).thenReturn(true);
        }
        return value;
    }

    @Test
    void claimsIndexTaskAndMarksSuccess() {
        TaskWorkerStore workers = mock(TaskWorkerStore.class);
        IndexingService indexing = mock(IndexingService.class);
        UUID owner = UUID.randomUUID();
        String taskId = UUID.randomUUID().toString();
        when(workers.claim("worker", "index", 300)).thenReturn(Map.of(
                "id", taskId, "user_id", owner.toString(), "type", "index.file",
                "payload_json", "{\"path\":\"notes.txt\"}"
        ));
        when(indexing.indexFile(owner, "notes.txt")).thenReturn(Map.of("indexed", true));
        IndexTaskHandler handler = new IndexTaskHandler(workers, indexing, new ObjectMapper());

        org.assertj.core.api.Assertions.assertThat(handler.runOnce("worker")).isTrue();

        verify(indexing).indexFile(owner, "notes.txt");
        verify(workers).succeed(eq("worker"), eq(taskId), eq(Map.of("indexed", true)));
    }

    @Test
    void unsupportedTaskIsFailedWithoutEscapingWorkerLoop() {
        TaskWorkerStore workers = mock(TaskWorkerStore.class);
        IndexingService indexing = mock(IndexingService.class);
        String taskId = UUID.randomUUID().toString();
        when(workers.claim("worker", "index", 300)).thenReturn(Map.of(
                "id", taskId, "user_id", UUID.randomUUID().toString(), "type", "unknown.task",
                "payload_json", "{}"
        ));
        IndexTaskHandler handler = new IndexTaskHandler(workers, indexing, new ObjectMapper());

        org.assertj.core.api.Assertions.assertThat(handler.runOnce("worker")).isTrue();

        verify(workers).fail(eq("worker"), eq(taskId), eq("unsupported task type: unknown.task"));
    }

    @Test
    void cancellationRejectedProgressStopsWorkAndCannotSucceed() {
        TaskWorkerStore workers = mock(TaskWorkerStore.class);
        IndexingService indexing = mock(IndexingService.class);
        UUID owner = UUID.randomUUID();
        String taskId = UUID.randomUUID().toString();
        when(workers.claim("worker", "index", 300)).thenReturn(Map.of(
                "id", taskId, "user_id", owner.toString(), "type", "index.file",
                "payload_json", "{\"path\":\"notes.txt\"}"
        ));
        when(workers.updateProgress(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyInt()))
                .thenReturn(false);
        IndexTaskHandler handler = new IndexTaskHandler(workers, indexing, new ObjectMapper());

        assertThat(handler.runOnce("worker")).isTrue();

        verify(indexing, never()).indexFile(any(), anyString());
        verify(workers, never()).succeed(eq("worker"), eq(taskId), any());
        verify(workers).fail(eq("worker"), eq(taskId), eq("task lease lost or cancellation requested"));
    }

    @Test
    void embedsAFileAfterIndexingIt() {
        TaskWorkerStore workers = mock(TaskWorkerStore.class);
        IndexingService indexing = mock(IndexingService.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        UUID owner = UUID.randomUUID();
        String taskId = UUID.randomUUID().toString();
        when(workers.claim("worker", "index", 300)).thenReturn(Map.of(
                "id", taskId, "user_id", owner.toString(), "type", "index.file",
                "payload_json", "{\"path\":\"notes.txt\"}"
        ));
        when(indexing.indexFile(owner, "notes.txt")).thenReturn(Map.of("indexed", true));
        when(embeddings.embed(eq(owner), eq(List.of("notes.txt")), eq(64), eq(false), any(TaskProgressReporter.class)))
                .thenReturn(Map.of("vectorized", true, "embedded", 1));
        IndexTaskHandler handler = new IndexTaskHandler(workers, indexing, new ObjectMapper(), embeddings);

        org.assertj.core.api.Assertions.assertThat(handler.runOnce("worker")).isTrue();

        verify(embeddings).embed(eq(owner), eq(List.of("notes.txt")), eq(64), eq(false), any(TaskProgressReporter.class));
        verify(workers).succeed(eq("worker"), eq(taskId), eq(Map.of(
                "indexed", true, "embedding", Map.of("vectorized", true, "embedded", 1))));
    }

    @Test
    void indexesAndEmbedsTheRequestedFileList() {
        TaskWorkerStore workers = mock(TaskWorkerStore.class);
        IndexingService indexing = mock(IndexingService.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        UUID owner = UUID.randomUUID();
        String taskId = UUID.randomUUID().toString();
        List<String> files = List.of("a.txt", "b.md");
        when(workers.claim("worker", "index", 300)).thenReturn(Map.of(
                "id", taskId, "user_id", owner.toString(), "type", "index.embed",
                "payload_json", "{\"files\":[\"a.txt\",\"b.md\"]}"
        ));
        when(indexing.indexFile(owner, "a.txt")).thenReturn(Map.of("path", "a.txt", "indexed", true));
        when(indexing.indexFile(owner, "b.md")).thenReturn(Map.of("path", "b.md", "indexed", true));
        when(embeddings.embed(eq(owner), eq(files), eq(64), eq(false), any(TaskProgressReporter.class)))
                .thenReturn(Map.of("vectorized", true, "embedded", 2));
        IndexTaskHandler handler = new IndexTaskHandler(workers, indexing, new ObjectMapper(), embeddings);

        org.assertj.core.api.Assertions.assertThat(handler.runOnce("worker")).isTrue();

        verify(embeddings).embed(eq(owner), eq(files), eq(64), eq(false), any(TaskProgressReporter.class));
        verify(workers).succeed(eq("worker"), eq(taskId), eq(Map.of(
                "files", List.of(
                        Map.of("path", "a.txt", "indexed", true),
                        Map.of("path", "b.md", "indexed", true)),
                "embedding", Map.of("vectorized", true, "embedded", 2))));
    }

    @Test
    void marksExplicitEmbeddingTaskFailedWhenProviderReturnsFailureResult() {
        TaskWorkerStore workers = mock(TaskWorkerStore.class);
        IndexingService indexing = mock(IndexingService.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        UUID owner = UUID.randomUUID();
        String taskId = UUID.randomUUID().toString();
        when(workers.claim("worker", "index", 300)).thenReturn(Map.of(
                "id", taskId, "user_id", owner.toString(), "type", "index.embed",
                "payload_json", "{}"));
        when(embeddings.embed(eq(owner), eq(List.of()), eq(64), eq(false), any(TaskProgressReporter.class)))
                .thenReturn(Map.of("vectorized", false, "reason", "provider_http_502", "embedded", 0));
        IndexTaskHandler handler = new IndexTaskHandler(workers, indexing, new ObjectMapper(), embeddings);

        assertThat(handler.runOnce("worker")).isTrue();

        verify(workers).fail(eq("worker"), eq(taskId), eq("embedding_failed: provider_http_502"));
        verify(workers, never()).succeed(eq("worker"), eq(taskId), any());
    }

    @Test
    void clearsVectorsThroughDedicatedBackgroundTask() {
        TaskWorkerStore workers = mock(TaskWorkerStore.class);
        IndexingService indexing = mock(IndexingService.class);
        UUID owner = UUID.randomUUID();
        String taskId = UUID.randomUUID().toString();
        when(workers.claim("worker", "index", 300)).thenReturn(Map.of(
                "id", taskId, "user_id", owner.toString(), "type", "index.clear_vectors",
                "payload_json", "{}"));
        when(indexing.clearVectors(owner)).thenReturn(Map.of("cleared_vectors", 42, "status", "vectors_cleared"));
        IndexTaskHandler handler = new IndexTaskHandler(workers, indexing, new ObjectMapper());

        assertThat(handler.runOnce("worker")).isTrue();

        verify(indexing).clearVectors(owner);
        verify(workers).succeed(eq("worker"), eq(taskId), eq(Map.of(
                "cleared_vectors", 42, "status", "vectors_cleared")));
    }

    @Test
    void failsVisionTaskWithoutCallingEmbeddingWhenEveryDescriptionFails() {
        TaskWorkerStore workers = mock(TaskWorkerStore.class);
        IndexingService indexing = mock(IndexingService.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        VisionDescriptionService vision = mock(VisionDescriptionService.class);
        UUID owner = UUID.randomUUID();
        String taskId = UUID.randomUUID().toString();
        List<String> paths = List.of("photos/a.jpg", "photos/b.jpg");
        when(workers.claim("worker", "index", 300)).thenReturn(Map.of(
                "id", taskId, "user_id", owner.toString(), "type", "index.vision",
                "payload_json", "{\"files\":[\"photos/a.jpg\",\"photos/b.jpg\"]}"));
        when(vision.describeFile(owner, "photos/a.jpg")).thenThrow(new IllegalStateException("provider_500"));
        when(vision.describeFile(owner, "photos/b.jpg")).thenThrow(new IllegalStateException("provider_500"));
        IndexTaskHandler handler = new IndexTaskHandler(
                workers, indexing, new ObjectMapper(), embeddings, null, vision);

        assertThat(handler.runOnce("worker")).isTrue();

        verify(embeddings, never()).embed(eq(owner), eq(paths), eq(64), eq(false), any());
        verify(embeddings, never()).embed(eq(owner), eq(List.of()), eq(64), eq(false), any());
        verify(workers).fail(eq("worker"), eq(taskId), eq("vision_all_files_failed"));
        verify(workers, never()).succeed(eq("worker"), eq(taskId), any());
    }

    @Test
    void preservesPerFileVisionFailuresWhenAnotherFileSucceeds() {
        TaskWorkerStore workers = mock(TaskWorkerStore.class);
        IndexingService indexing = mock(IndexingService.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        VisionDescriptionService vision = mock(VisionDescriptionService.class);
        UUID owner = UUID.randomUUID();
        String taskId = UUID.randomUUID().toString();
        when(workers.claim("worker", "index", 300)).thenReturn(Map.of(
                "id", taskId, "user_id", owner.toString(), "type", "index.vision",
                "payload_json", "{\"files\":[\"photos/a.jpg\",\"photos/b.jpg\"]}"));
        when(vision.describeFile(owner, "photos/a.jpg")).thenThrow(new IllegalStateException("provider_500"));
        when(vision.describeFile(owner, "photos/b.jpg")).thenReturn(Map.of(
                "description", Map.of("summary", "receipt"), "model", "vision-test"));
        when(indexing.indexDescription(eq(owner), eq("photos/b.jpg"), any()))
                .thenReturn(Map.of("path", "photos/b.jpg", "indexed", true));
        when(embeddings.embed(eq(owner), eq(List.of("photos/b.jpg")), eq(64), eq(false),
                any(TaskProgressReporter.class)))
                .thenReturn(Map.of("vectorized", true, "embedded", 1));
        IndexTaskHandler handler = new IndexTaskHandler(
                workers, indexing, new ObjectMapper(), embeddings, null, vision);

        assertThat(handler.runOnce("worker")).isTrue();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> resultCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(workers).succeed(eq("worker"), eq(taskId), resultCaptor.capture());
        Map<String, Object> result = resultCaptor.getValue();
        assertThat(result).containsEntry("described", 1)
                .containsEntry("embedding", Map.of("vectorized", true, "embedded", 1));
        assertThat((List<Map<String, Object>>) result.get("files"))
                .anySatisfy(item -> assertThat(item).containsEntry("path", "photos/a.jpg")
                        .containsEntry("indexed", false).containsEntry("error", "provider_500"))
                .anySatisfy(item -> assertThat(item).containsEntry("path", "photos/b.jpg")
                        .containsEntry("indexed", true).containsEntry("model", "vision-test"));
    }

    @Test
    void preservesThreadInterruptWhenVisionRequestIsInterrupted() {
        TaskWorkerStore workers = mock(TaskWorkerStore.class);
        IndexingService indexing = mock(IndexingService.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        VisionDescriptionService vision = mock(VisionDescriptionService.class);
        UUID owner = UUID.randomUUID();
        String taskId = UUID.randomUUID().toString();
        when(workers.claim("worker", "index", 300)).thenReturn(Map.of(
                "id", taskId, "user_id", owner.toString(), "type", "index.vision",
                "payload_json", "{\"files\":[\"photos/a.jpg\"]}"));
        when(vision.describeFile(owner, "photos/a.jpg")).thenAnswer(ignored -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("vision_request_failed", new InterruptedException("stopped"));
        });
        IndexTaskHandler handler = new IndexTaskHandler(
                workers, indexing, new ObjectMapper(), embeddings, null, vision);

        try {
            assertThat(handler.runOnce("worker")).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(workers).fail(eq("worker"), eq(taskId), eq("vision_interrupted"));
            verify(embeddings, never()).embed(any(), any(), eq(64), eq(false), any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void executesAutomationTaskThroughExecutor() {
        TaskWorkerStore workers = mock(TaskWorkerStore.class);
        IndexingService indexing = mock(IndexingService.class);
        AutomationTaskExecutor automation = mock(AutomationTaskExecutor.class);
        UUID owner = UUID.randomUUID();
        String taskId = UUID.randomUUID().toString();
        Map<String, Object> payload = Map.of("rules", List.of("整理下载目录"));
        when(workers.claim("worker", "index", 300)).thenReturn(null);
        when(workers.claim("worker", "default", 300)).thenReturn(null);
        when(workers.claim("worker", "automation", 300)).thenReturn(Map.of(
                "id", taskId, "user_id", owner.toString(), "type", "automation.run",
                "payload_json", "{\"rules\":[\"整理下载目录\"]}"
        ));
        when(automation.execute(owner, payload)).thenReturn(Map.of("ok", true, "steps", 1));
        IndexTaskHandler handler = new IndexTaskHandler(workers, indexing, new ObjectMapper(), null, automation);

        org.assertj.core.api.Assertions.assertThat(handler.runOnce("worker")).isTrue();

        verify(automation).execute(owner, payload);
        verify(workers).succeed(eq("worker"), eq(taskId), eq(Map.of("ok", true, "steps", 1)));
    }

    @Test
    void executesChatRunThroughDurableWorkerExecutor() {
        TaskWorkerStore workers = mock(TaskWorkerStore.class);
        IndexingService indexing = mock(IndexingService.class);
        ChatTaskExecutor chat = mock(ChatTaskExecutor.class);
        UUID owner = UUID.randomUUID();
        String taskId = UUID.randomUUID().toString();
        Map<String, Object> payload = Map.of("session_id", UUID.randomUUID().toString(), "message", "整理文件");
        when(workers.claim("worker", "index", 300)).thenReturn(null);
        when(workers.claim("worker", "default", 300)).thenReturn(null);
        when(workers.claim("worker", "maintenance", 300)).thenReturn(null);
        when(workers.claim("worker", "automation", 300)).thenReturn(Map.of(
                "id", taskId, "user_id", owner.toString(), "type", "chat.run",
                "payload_json", "{\"session_id\":\"" + payload.get("session_id") + "\",\"message\":\"整理文件\"}"
        ));
        when(chat.execute(eq(owner), eq(payload), any(TaskProgressReporter.class)))
                .thenReturn(Map.of("ok", true, "session_id", payload.get("session_id"), "reply", "已完成"));
        IndexTaskHandler handler = new IndexTaskHandler(
                workers, indexing, new ObjectMapper(), null, null, null, null, null, chat);

        assertThat(handler.runOnce("worker")).isTrue();

        verify(chat).execute(eq(owner), eq(payload), any(TaskProgressReporter.class));
        verify(workers).succeed(eq("worker"), eq(taskId), eq(Map.of(
                "ok", true, "session_id", payload.get("session_id"), "reply", "已完成")));
    }

    @Test
    void executesDailyMaintenanceThroughAllOwnerScopedServices() {
        TaskWorkerStore workers = mock(TaskWorkerStore.class);
        IndexingService indexing = mock(IndexingService.class);
        FileStorageService files = mock(FileStorageService.class);
        TaskStore tasks = mock(TaskStore.class);
        UUID owner = UUID.randomUUID();
        String taskId = UUID.randomUUID().toString();
        when(workers.claim("worker", "index", 300)).thenReturn(null);
        when(workers.claim("worker", "default", 300)).thenReturn(null);
        when(workers.claim("worker", "maintenance", 300)).thenReturn(Map.of(
                "id", taskId, "user_id", owner.toString(), "type", "maintenance.daily",
                "payload_json", "{}"
        ));
        when(indexing.cleanup(owner)).thenReturn(Map.of("removed", 2));
        when(files.cleanupTrash(owner, 30)).thenReturn(Map.of("removed", 3));
        when(tasks.pruneHistory(owner, 30, 2000)).thenReturn(Map.of("jobs", 4));
        IndexTaskHandler handler = new IndexTaskHandler(
                workers, indexing, new ObjectMapper(), null,
                (AutomationTaskExecutor) null, (com.agentdrive.vision.VisionDescriptionService) null,
                files, tasks);

        org.assertj.core.api.Assertions.assertThat(handler.runOnce("worker")).isTrue();

        verify(indexing).cleanup(owner);
        verify(files).cleanupTrash(owner, 30);
        verify(tasks).pruneHistory(owner, 30, 2000);
        verify(workers).succeed(eq("worker"), eq(taskId), eq(Map.of(
                "index", Map.of("removed", 2), "trash_removed", 3, "task_history", Map.of("jobs", 4))));
    }
}
