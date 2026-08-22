package com.agentdrive.index;

import com.agentdrive.vision.VisionDescriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        when(index.files(owner, "docs")).thenReturn(List.of(Map.of("path", "docs/a.md")));
        when(index.statistics(eq(owner), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new IndexStore.Stats(1, 1, 0, 0, 1, 0));
        when(embeddings.embed(owner, List.of("docs/a.md"), 64, false))
                .thenReturn(Map.of("vectorized", true, "embedded", 1));
        IndexDomainService service = new IndexDomainService(index, mock(IndexingService.class), embeddings,
                config, mock(VisionDescriptionService.class), new ObjectMapper());

        assertThat(service.overview(owner, "docs", 10))
                .containsEntry("prefix", "docs")
                .containsKey("stats")
                .containsKey("embedding");
        assertThat(service.vectorize(owner, List.of("docs/a.md"), false, 64))
                .containsEntry("embedded", 1);
    }

    @Test
    void recordsBatchVisionExecutionThroughTaskMonitorBehindIndexApi() {
        IndexStore index = mock(IndexStore.class);
        IndexExecutionMonitor monitor = mock(IndexExecutionMonitor.class);
        UUID owner = UUID.randomUUID();
        Map<String, Object> task = Map.of("id", UUID.randomUUID().toString(), "status", "queued");
        when(monitor.submit(eq(owner), eq("index.vision"), eq("index.vision"),
                eq(List.of("photos/a.png", "photos/b.png")), eq(false), eq("")))
                .thenReturn(Optional.of(new IndexExecutionMonitor.Submission(true, "queued",
                        String.valueOf(task.get("id")), task)));
        IndexDomainService service = new IndexDomainService(index, mock(IndexingService.class),
                mock(EmbeddingService.class), emptyConfig(), mock(VisionDescriptionService.class),
                new ObjectMapper(), monitor);

        Map<String, Object> result = service.indexVision(owner,
                List.of("photos/a.png", "photos/b.png"), false);

        assertThat(result).containsEntry("operation", "index.vision")
                .containsEntry("accepted", true)
                .containsEntry("status", "queued")
                .containsKey("monitor");
    }

    private static IndexDomainService service(IndexStore index, IndexingService indexing, EmbeddingService embeddings) {
        EmbeddingRuntimeConfig config = emptyConfig();
        return new IndexDomainService(index, indexing, embeddings, config,
                mock(VisionDescriptionService.class), new ObjectMapper());
    }

    private static EmbeddingRuntimeConfig emptyConfig() {
        EmbeddingRuntimeConfig config = mock(EmbeddingRuntimeConfig.class);
        when(config.find(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        return config;
    }

}
