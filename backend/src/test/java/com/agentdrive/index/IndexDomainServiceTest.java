package com.agentdrive.index;

import com.agentdrive.vision.VisionDescriptionPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexDomainServiceTest {
    @Test
    void clearsVectorsDirectlyThroughIndexDomainWithoutCreatingTask() {
        IndexStore index = mock(IndexStore.class);
        when(index.clearEmbeddings(org.mockito.ArgumentMatchers.any(UUID.class))).thenReturn(42);
        IndexDomainService service = service(index, mock(IndexingService.class), mock(EmbeddingService.class));
        UUID owner = UUID.randomUUID();

        assertThat(service.clearVectors(owner))
                .containsEntry("cleared_vectors", 42)
                .containsEntry("status", "vectors_cleared");
        verify(index).clearEmbeddings(owner);
    }

    @Test
    void exposesIndexOverviewAndDirectVectorizationAsDomainOperations() {
        IndexStore index = mock(IndexStore.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        EmbeddingRuntimeConfig config = mock(EmbeddingRuntimeConfig.class);
        UUID owner = UUID.randomUUID();
        when(config.find(owner)).thenReturn(Optional.of(
                new EmbeddingRuntimeConfig.Config("jina", "https://api.jina.ai/v1", "jina-embeddings-v3", "secret")));
        when(index.files(owner, "docs", 11)).thenReturn(List.of(Map.of("path", "docs/a.md")));
        when(index.statistics(eq(owner), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new IndexStore.Stats(1, 1, 0, 0, 1, 0));
        when(embeddings.embed(owner, List.of("docs/a.md"), 64, false))
                .thenReturn(Map.of("vectorized", true, "embedded", 1));
        IndexDomainService service = new IndexDomainService(index, mock(IndexingService.class), embeddings,
                config, mock(VisionDescriptionPort.class));

        assertThat(service.overview(owner, "docs", 10))
                .containsEntry("prefix", "docs")
                .containsKey("stats")
                .containsKey("embedding");
        assertThat(service.vectorize(owner, List.of("docs/a.md"), false, 64))
                .containsEntry("embedded", 1);
    }

    @Test
    void executesBatchVisionDirectlyWithoutTaskMonitor() {
        IndexStore index = mock(IndexStore.class);
        IndexingService indexing = mock(IndexingService.class);
        VisionDescriptionPort vision = mock(VisionDescriptionPort.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        UUID owner = UUID.randomUUID();
        when(vision.describeFiles(eq(owner), eq(List.of("photos/a.png", "photos/b.png")))).thenReturn(Map.of(
                "ok", true,
                "items", List.of(
                        Map.of("path", "photos/a.png", "description", "一张收据截图", "model", "vision-test"),
                        Map.of("path", "photos/b.png", "description", "一个产品包装盒", "model", "vision-test"))));
        when(index.visionPathsNeedingDescription(owner, List.of("photos/a.png", "photos/b.png")))
                .thenReturn(List.of("photos/a.png", "photos/b.png"));
        when(indexing.indexDescription(eq(owner), eq("photos/a.png"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Map.of("indexed", true));
        when(indexing.indexDescription(eq(owner), eq("photos/b.png"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Map.of("indexed", true));
        when(embeddings.embed(eq(owner), eq(List.of("photos/a.png")), eq(64), eq(false)))
                .thenReturn(Map.of("vectorized", true));
        when(embeddings.embed(eq(owner), eq(List.of("photos/b.png")), eq(64), eq(false)))
                .thenReturn(Map.of("vectorized", true));
        IndexDomainService service = new IndexDomainService(index, indexing,
                embeddings, emptyConfig(), vision);

        Map<String, Object> result = service.indexVision(owner,
                List.of("photos/a.png", "photos/b.png"), false);

        assertThat(result).containsEntry("operation", "index.vision")
                .containsEntry("status", "succeeded")
                .containsKey("items");
    }

    @Test
    void expandsDirectoryVisionRequestToSupportedImagesAndSkipsUnsupportedFormats() {
        IndexStore index = mock(IndexStore.class);
        IndexingService indexing = mock(IndexingService.class);
        VisionDescriptionPort vision = mock(VisionDescriptionPort.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        UUID owner = UUID.randomUUID();
        when(index.files(owner, "photos")).thenReturn(List.of(
                Map.of("path", "photos/a.png"),
                Map.of("path", "photos/b.heic"),
                Map.of("path", "photos/note.txt")));
        when(vision.isImage("photos/a.png")).thenReturn(true);
        when(vision.isImage("photos/b.heic")).thenReturn(false);
        when(vision.isImage("photos/note.txt")).thenReturn(false);
        when(index.visionPathsNeedingDescription(owner, List.of("photos/a.png")))
                .thenReturn(List.of("photos/a.png"));
        when(vision.describeFiles(owner, List.of("photos/a.png"))).thenReturn(Map.of(
                "ok", true,
                "items", List.of(Map.of("path", "photos/a.png", "description", "一张图片"))));
        when(indexing.indexDescription(eq(owner), eq("photos/a.png"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Map.of("indexed", true));
        when(embeddings.embed(eq(owner), eq(List.of("photos/a.png")), eq(64), eq(false)))
                .thenReturn(Map.of("vectorized", true));

        Map<String, Object> result = new IndexDomainService(index, indexing, embeddings,
                emptyConfig(), vision).indexVision(owner, List.of("photos"), false);

        verify(vision).describeFiles(owner, List.of("photos/a.png"));
        assertThat(result).containsEntry("status", "partial").containsEntry("skipped", 1);
        assertThat((List<?>) result.get("items")).hasSize(1);
    }

    @Test
    void reportsDirectoryVisionProgressWithRealBatchCounters() {
        IndexStore index = mock(IndexStore.class);
        IndexingService indexing = mock(IndexingService.class);
        VisionDescriptionPort vision = mock(VisionDescriptionPort.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        UUID owner = UUID.randomUUID();
        when(index.files(owner, "photos")).thenReturn(List.of(
                Map.of("path", "photos/a.png"), Map.of("path", "photos/b.png")));
        when(vision.isImage("photos/a.png")).thenReturn(true);
        when(vision.isImage("photos/b.png")).thenReturn(true);
        when(index.visionPathsNeedingDescription(owner, List.of("photos/a.png", "photos/b.png")))
                .thenReturn(List.of("photos/a.png", "photos/b.png"));
        when(vision.describeFiles(eq(owner), eq(List.of("photos/a.png", "photos/b.png")),
                org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<Map<String, Object>> progress = invocation.getArgument(2);
            progress.accept(Map.of("phase", "vision", "message", "正在调用视觉模型分析图片",
                    "completed", 1, "total", 2, "succeeded", 1, "failed", 0));
            progress.accept(Map.of("phase", "vision", "message", "正在调用视觉模型分析图片",
                    "completed", 2, "total", 2, "succeeded", 2, "failed", 0));
            return Map.of("ok", true, "items", List.of(
                    Map.of("path", "photos/a.png", "description", "一张图片"),
                    Map.of("path", "photos/b.png", "description", "另一张图片")));
        });
        when(indexing.indexDescription(eq(owner), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(Map.of("indexed", true));
        when(embeddings.embed(eq(owner), org.mockito.ArgumentMatchers.anyList(), eq(64), eq(false)))
                .thenReturn(Map.of("vectorized", true));

        List<Map<String, Object>> progress = new ArrayList<>();
        Map<String, Object> result = new IndexDomainService(index, indexing, embeddings,
                emptyConfig(), vision).indexVision(owner, List.of("photos"), false, progress::add);

        assertThat(result).containsEntry("status", "succeeded");
        assertThat(progress).anySatisfy(item -> assertThat(item)
                .containsEntry("completed", 1).containsEntry("total", 2));
        assertThat(progress.get(progress.size() - 1))
                .containsEntry("completed", 2).containsEntry("succeeded", 2);
    }

    @Test
    void incrementalVisionSkipsFilesWithExistingCurrentVisionDocuments() {
        IndexStore index = mock(IndexStore.class);
        IndexingService indexing = mock(IndexingService.class);
        VisionDescriptionPort vision = mock(VisionDescriptionPort.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        UUID owner = UUID.randomUUID();
        List<String> requested = List.of("photos/existing.png", "photos/missing.png");
        when(index.files(owner, "photos")).thenReturn(requested.stream()
                .map(path -> Map.<String, Object>of("path", path)).toList());
        when(vision.isImage(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(index.visionPathsNeedingDescription(owner, requested)).thenReturn(List.of("photos/missing.png"));
        when(vision.describeFiles(eq(owner), eq(List.of("photos/missing.png")))).thenReturn(Map.of(
                "ok", true,
                "items", List.of(Map.of("path", "photos/missing.png", "description", "待处理图片"))));
        when(indexing.indexDescription(owner, "photos/missing.png", "待处理图片"))
                .thenReturn(Map.of("indexed", true));
        when(embeddings.embed(owner, List.of("photos/missing.png"), 64, false))
                .thenReturn(Map.of("vectorized", true));

        Map<String, Object> result = new IndexDomainService(index, indexing, embeddings,
                emptyConfig(), vision).indexVision(owner, List.of("photos"), false);

        verify(vision).describeFiles(owner, List.of("photos/missing.png"));
        assertThat(result).containsEntry("skipped_existing", 1).containsEntry("status", "succeeded");
    }

    @Test
    void returnsPartialStatusInsteadOfAbortingOnOneTextFileFailure() {
        IndexStore index = mock(IndexStore.class);
        IndexingService indexing = mock(IndexingService.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        UUID owner = UUID.randomUUID();
        when(indexing.indexFile(owner, "ok.txt")).thenReturn(Map.of(
                "path", "ok.txt", "indexed", true, "status", "indexed"));
        when(indexing.indexFile(owner, "broken.txt")).thenThrow(new IllegalStateException("extract failed"));

        Map<String, Object> result = new IndexDomainService(index, indexing, embeddings,
                emptyConfig(), mock(VisionDescriptionPort.class))
                .indexFiles(owner, List.of("ok.txt", "broken.txt"), false);

        assertThat(result).containsEntry("ok", false)
                .containsEntry("status", "partial")
                .containsEntry("failed", 1);
        assertThat((List<?>) result.get("items")).hasSize(2);
    }

    @Test
    void emptyVectorPathsMeanAllOwnerChunks() {
        IndexStore index = mock(IndexStore.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        EmbeddingRuntimeConfig config = emptyConfig();
        UUID owner = UUID.randomUUID();
        when(embeddings.embed(owner, List.of(), 64, false))
                .thenReturn(Map.of("vectorized", true, "embedded", 3));

        Map<String, Object> result = new IndexDomainService(index, mock(IndexingService.class), embeddings,
                config, mock(VisionDescriptionPort.class))
                .vectorize(owner, List.of(), false, 64);

        assertThat(result).containsEntry("vectorized", true).containsEntry("embedded", 3);
        verify(embeddings).embed(owner, List.of(), 64, false);
    }

    private static IndexDomainService service(IndexStore index, IndexingService indexing, EmbeddingService embeddings) {
        EmbeddingRuntimeConfig config = emptyConfig();
        return new IndexDomainService(index, indexing, embeddings, config,
                mock(VisionDescriptionPort.class));
    }

    private static EmbeddingRuntimeConfig emptyConfig() {
        EmbeddingRuntimeConfig config = mock(EmbeddingRuntimeConfig.class);
        when(config.find(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        return config;
    }

}
