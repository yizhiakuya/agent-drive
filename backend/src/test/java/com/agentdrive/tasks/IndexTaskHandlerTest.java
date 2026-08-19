package com.agentdrive.tasks;

import com.agentdrive.files.FileStorageService;
import com.agentdrive.index.IndexingService;
import com.agentdrive.index.EmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexTaskHandlerTest {
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
        when(embeddings.embed(owner, List.of("notes.txt"), 64, false))
                .thenReturn(Map.of("vectorized", true, "embedded", 1));
        IndexTaskHandler handler = new IndexTaskHandler(workers, indexing, new ObjectMapper(), embeddings);

        org.assertj.core.api.Assertions.assertThat(handler.runOnce("worker")).isTrue();

        verify(embeddings).embed(owner, List.of("notes.txt"), 64, false);
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
        when(embeddings.embed(owner, files, 64, false))
                .thenReturn(Map.of("vectorized", true, "embedded", 2));
        IndexTaskHandler handler = new IndexTaskHandler(workers, indexing, new ObjectMapper(), embeddings);

        org.assertj.core.api.Assertions.assertThat(handler.runOnce("worker")).isTrue();

        verify(embeddings).embed(owner, files, 64, false);
        verify(workers).succeed(eq("worker"), eq(taskId), eq(Map.of(
                "files", List.of(
                        Map.of("path", "a.txt", "indexed", true),
                        Map.of("path", "b.md", "indexed", true)),
                "embedding", Map.of("vectorized", true, "embedded", 2))));
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
