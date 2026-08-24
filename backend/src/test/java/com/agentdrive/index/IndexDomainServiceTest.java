package com.agentdrive.index;

import com.agentdrive.vision.VisionDescriptionPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
