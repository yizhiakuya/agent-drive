package com.agentdrive.index;

import com.agentdrive.files.FileStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexingServiceTest {
    @TempDir
    Path temp;

    @Test
    void extractsTextChunksAndPassesOwnerToPersistence() throws Exception {
        FileStorageService files = mock(FileStorageService.class);
        IndexStore index = mock(IndexStore.class);
        UUID owner = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        Path source = temp.resolve("notes.txt");
        String content = "a".repeat(2100);
        Files.writeString(source, content, StandardCharsets.UTF_8);
        when(index.file(owner, "notes.txt")).thenReturn(Map.of(
                "id", fileId.toString(), "size_bytes", content.length(), "revision", 3L
        ));
        when(files.fileForRead(owner, "notes.txt")).thenReturn(source);
        IndexingService service = new IndexingService(files, index);

        Map<String, Object> result = service.indexFile(owner, "notes.txt");

        assertThat(result).containsEntry("indexed", true).containsEntry("status", "indexed");
        ArgumentCaptor<List<String>> chunks = ArgumentCaptor.forClass(List.class);
        verify(index).replaceDocument(eq(owner), eq(fileId), eq(3L), eq(content),
                eq("java-tika-v1"), chunks.capture(), eq("java-chunk-v1"));
        assertThat(chunks.getValue()).hasSize(2);
        assertThat(chunks.getValue().get(0)).hasSize(2000);
        assertThat(chunks.getValue().get(1)).hasSize(300);
    }

    @Test
    void routesImagesToVisionInsteadOfTikaOrOcr() throws Exception {
        FileStorageService files = mock(FileStorageService.class);
        IndexStore index = mock(IndexStore.class);
        UUID owner = UUID.randomUUID();
        when(index.file(owner, "broken.jpg")).thenReturn(Map.of(
                "id", UUID.randomUUID().toString(), "size_bytes", 4L, "revision", 1L
        ));

        Map<String, Object> result = new IndexingService(files, index).indexFile(owner, "broken.jpg");

        assertThat(result).containsEntry("indexed", false)
                .containsEntry("status", "vision_required")
                .containsEntry("vector_type", "vision");
        verify(files, org.mockito.Mockito.never()).fileForRead(owner, "broken.jpg");
        verify(index, org.mockito.Mockito.never()).replaceDocument(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void storesVisionDescriptionAsVisionDocumentType() {
        FileStorageService files = mock(FileStorageService.class);
        IndexStore index = mock(IndexStore.class);
        UUID owner = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        when(index.file(owner, "photo.jpg")).thenReturn(Map.of(
                "id", fileId.toString(), "size_bytes", 4L, "revision", 7L
        ));

        Map<String, Object> result = new IndexingService(files, index)
                .indexDescription(owner, "photo.jpg", "{\"summary\":\"receipt\"}");

        assertThat(result).containsEntry("indexed", true)
                .containsEntry("status", "vision_indexed")
                .containsEntry("vector_type", "vision");
        verify(index).replaceDocument(eq(owner), eq(fileId), eq(7L),
                eq(IndexStore.VISION_DOCUMENT_TYPE), eq("{\"summary\":\"receipt\"}"),
                eq("vision-description-v1"), any(), eq("vision-chunk-v1"));
    }
}
