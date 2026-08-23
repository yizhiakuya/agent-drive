package com.agentdrive.infrastructure.persistence;

import com.agentdrive.infrastructure.persistence.mapper.FileMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MybatisFileStorageServiceListTest {
    private final UUID owner = UUID.randomUUID();
    private Path root;
    private FileMapper mapper;
    private MybatisFileStorageService files;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("agent-drive-list-test-");
        mapper = mock(FileMapper.class);
        files = new MybatisFileStorageService(mapper, root, 10 * 1024 * 1024L);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (root != null) {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    void synchronizesOnlyChangedMetadataWithOneBatchWrite() throws Exception {
        Path ownerRoot = root.resolve(owner.toString());
        Files.createDirectories(ownerRoot);
        Files.writeString(ownerRoot.resolve("same.txt"), "same");
        Files.writeString(ownerRoot.resolve("changed.txt"), "changed");
        when(mapper.selectByPaths(eq(owner.toString()), anyList())).thenReturn(List.of(
                Map.of("path", "same.txt", "is_dir", false, "size_bytes", 4L)
        ));

        Map<String, Object> result = files.list(owner, "");

        assertThat(result.get("items")).asList().extracting("path")
                .containsExactly("changed.txt", "same.txt");
        verify(mapper).selectByPaths(eq(owner.toString()), eq(List.of("changed.txt", "same.txt")));
        verify(mapper).upsertMetadataBatch(eq(owner.toString()), eq(List.of(
                Map.of("path", "changed.txt", "isDir", false, "size", 7L)
        )));
        verify(mapper, never()).upsertMetadata(eq(owner.toString()), eq("same.txt"), eq(false), eq(4L));
    }

    @Test
    void capsRecursiveSearchResults() throws Exception {
        Path ownerRoot = root.resolve(owner.toString());
        Files.createDirectories(ownerRoot.resolve("nested"));
        for (int i = 0; i < 1_050; i++) {
            Files.writeString(ownerRoot.resolve("nested/item-" + i + ".txt"), String.valueOf(i));
        }
        when(mapper.selectByPaths(eq(owner.toString()), anyList())).thenReturn(List.of());

        Map<String, Object> result = files.list(owner, "", "item-");

        assertThat(result.get("items")).asList().hasSize(1_000);
        verify(mapper).upsertMetadataBatch(eq(owner.toString()), org.mockito.ArgumentMatchers.argThat(
                entries -> entries.size() == 1_000));
    }

    @Test
    void statisticsCountsVisibleFilesAndFoldersWithoutInternalArtifacts() throws Exception {
        Path ownerRoot = root.resolve(owner.toString());
        Files.createDirectories(ownerRoot.resolve("photos/2026-07-01"));
        Files.writeString(ownerRoot.resolve("photos/2026-07-01/a.txt"), "abc");
        Files.writeString(ownerRoot.resolve("photos/2026-07-01/b.txt"), "12345");
        Files.createDirectories(ownerRoot.resolve("photos/.trash"));
        Files.writeString(ownerRoot.resolve("photos/.trash/hidden.txt"), "hidden");

        Map<String, Object> result = files.statistics(owner, "photos");

        assertThat(result)
                .containsEntry("path", "photos")
                .containsEntry("recursive", true)
                .containsEntry("file_count", 2L)
                .containsEntry("folder_count", 1L)
                .containsEntry("total_size_bytes", 8L)
                .containsEntry("complete", true)
                .containsKey("snapshot_at");
    }
}
