package com.agentdrive.infrastructure.persistence;

import com.agentdrive.files.FileStorageException;
import com.agentdrive.infrastructure.persistence.mapper.FileMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MybatisFileStorageServiceCopyTest {
    private final UUID owner = UUID.randomUUID();
    private Path root;
    private FileMapper mapper;
    private MybatisFileStorageService files;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("agent-drive-copy-test-");
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
    void publishesACompleteDirectoryAndCleansArtifacts() throws Exception {
        Path ownerRoot = prepareDirectory("source");
        Files.createDirectories(ownerRoot.resolve("source/nested"));
        Files.writeString(ownerRoot.resolve("source/nested/note.txt"), "alpha");
        when(mapper.selectByPath(owner.toString(), "source")).thenReturn(Map.of("revision", 1));

        files.copy(owner, "source", "archive", false);

        assertThat(Files.readString(ownerRoot.resolve("archive/nested/note.txt"))).isEqualTo("alpha");
        try (var paths = Files.list(ownerRoot)) {
            assertThat(paths.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".copy"))
                    .toList()).isEmpty();
        }
    }

    @Test
    void copyFailureLeavesExistingTargetUnchanged() throws Exception {
        Path ownerRoot = prepareDirectory("source");
        Files.writeString(ownerRoot.resolve("source/ok.txt"), "new");
        Files.createDirectories(ownerRoot.resolve("target"));
        Files.writeString(ownerRoot.resolve("target/keep.txt"), "old");
        try {
            Files.createSymbolicLink(ownerRoot.resolve("source/broken-link"), Path.of("missing-target"));
        } catch (UnsupportedOperationException | java.io.IOException error) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable: " + error.getMessage());
        }
        when(mapper.selectByPath(owner.toString(), "source")).thenReturn(Map.of("revision", 1));

        assertThatThrownBy(() -> files.copy(owner, "source", "target", true))
                .isInstanceOf(FileStorageException.class)
                .satisfies(error -> assertThat(((FileStorageException) error).status()).isEqualTo(403));

        assertThat(Files.readString(ownerRoot.resolve("target/keep.txt"))).isEqualTo("old");
        assertThat(Files.exists(ownerRoot.resolve("target/ok.txt"))).isFalse();
    }

    private Path prepareDirectory(String name) throws Exception {
        Path ownerRoot = root.resolve(owner.toString());
        Files.createDirectories(ownerRoot.resolve(name));
        return ownerRoot;
    }
}
