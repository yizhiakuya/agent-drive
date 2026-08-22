package com.agentdrive.infrastructure.persistence;

import com.agentdrive.infrastructure.persistence.mapper.FileMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MybatisFileStorageServiceMutationTest {
    private final UUID owner = UUID.randomUUID();
    private Path root;
    private Path ownerRoot;
    private FileMapper mapper;
    private MybatisFileStorageService files;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("agent-drive-mutation-test-");
        ownerRoot = Files.createDirectories(root.resolve(owner.toString()));
        mapper = mock(FileMapper.class);
        files = new MybatisFileStorageService(mapper, root, 10 * 1024 * 1024L);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        if (root != null) {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void uploadDatabaseFailureRestoresOverwrittenTarget() throws Exception {
        Path target = ownerRoot.resolve("note.txt");
        Files.writeString(target, "old", StandardCharsets.UTF_8);
        Path temp = files.createUploadTemp();
        Files.writeString(temp, "new", StandardCharsets.UTF_8);
        doThrow(new IllegalStateException("database unavailable"))
                .when(mapper).upsertContent(anyString(), anyString(), anyLong(), anyString(), anyString());

        assertThatThrownBy(() -> files.publishUpload(owner, "", "note.txt", temp, "", false))
                .isInstanceOf(IllegalStateException.class);

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("old");
        assertThat(Files.exists(temp)).isFalse();
        assertNoMutationArtifacts();
    }

    @Test
    void copyMetadataFailureRestoresPreviousTarget() throws Exception {
        Files.writeString(ownerRoot.resolve("source.txt"), "new", StandardCharsets.UTF_8);
        Files.writeString(ownerRoot.resolve("target.txt"), "old", StandardCharsets.UTF_8);
        doThrow(new IllegalStateException("metadata unavailable"))
                .when(mapper).deletePrefix(owner.toString(), "target.txt");

        assertThatThrownBy(() -> files.copy(owner, "source.txt", "target.txt", true))
                .isInstanceOf(IllegalStateException.class);

        assertThat(Files.readString(ownerRoot.resolve("source.txt"), StandardCharsets.UTF_8)).isEqualTo("new");
        assertThat(Files.readString(ownerRoot.resolve("target.txt"), StandardCharsets.UTF_8)).isEqualTo("old");
        assertNoMutationArtifacts();
    }

    @Test
    void renameMovesFavoriteAndRecentAccessPrefixesWithTheFile() throws Exception {
        Files.writeString(ownerRoot.resolve("old.txt"), "content", StandardCharsets.UTF_8);

        files.rename(owner, "old.txt", "new.txt");

        verify(mapper).deleteFavoritePrefix(owner.toString(), "new.txt");
        verify(mapper).deleteAccessPrefix(owner.toString(), "new.txt");
        verify(mapper).moveFavoritePrefix(owner.toString(), "old.txt", "new.txt");
        verify(mapper).moveAccessPrefix(owner.toString(), "old.txt", "new.txt");
        assertThat(Files.exists(ownerRoot.resolve("new.txt"))).isTrue();
        assertThat(Files.exists(ownerRoot.resolve("old.txt"))).isFalse();
    }

    @Test
    void trashDatabaseFailureRestoresSource() throws Exception {
        Path source = ownerRoot.resolve("photo.jpg");
        Files.writeString(source, "photo", StandardCharsets.UTF_8);
        when(mapper.selectByPath(owner.toString(), "photo.jpg")).thenReturn(Map.of("revision", 7L));
        doThrow(new IllegalStateException("trash insert unavailable"))
                .when(mapper).insertTrash(anyString(), anyString(), anyString(), anyString(), anyLong());

        assertThatThrownBy(() -> files.deleteToTrash(owner, "photo.jpg"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(Files.readString(source, StandardCharsets.UTF_8)).isEqualTo("photo");
        try (var trashItems = Files.list(ownerRoot.resolve(".trash"))) {
            assertThat(trashItems.toList()).isEmpty();
        }
        assertNoMutationArtifacts();
    }

    @Test
    void restoreDatabaseFailureMovesContentBackIntoTrash() throws Exception {
        String trashId = UUID.randomUUID().toString();
        Path stored = Files.createDirectories(ownerRoot.resolve(".trash")).resolve(trashId);
        Files.writeString(stored, "archived", StandardCharsets.UTF_8);
        when(mapper.selectTrashByIdentifier(owner.toString(), trashId)).thenReturn(Map.of(
                "trash_id", trashId,
                "original_path", "restored.txt",
                "stored_path", ".trash/" + trashId
        ));
        doThrow(new IllegalStateException("trash delete unavailable"))
                .when(mapper).deleteTrash(owner.toString(), trashId);

        assertThatThrownBy(() -> files.restoreTrash(owner, trashId))
                .isInstanceOf(IllegalStateException.class);

        assertThat(Files.exists(ownerRoot.resolve("restored.txt"))).isFalse();
        assertThat(Files.readString(stored, StandardCharsets.UTF_8)).isEqualTo("archived");
        assertNoMutationArtifacts();
    }

    @Test
    void transactionRollbackRestoresTargetAndReleasesStorageLock() throws Exception {
        Path target = ownerRoot.resolve("note.txt");
        Files.writeString(target, "old", StandardCharsets.UTF_8);
        Path temp = files.createUploadTemp();
        Files.writeString(temp, "new", StandardCharsets.UTF_8);
        when(mapper.upsertContent(anyString(), anyString(), anyLong(), anyString(), anyString())).thenReturn(1);
        when(mapper.insertRevision(anyString(), anyString(), anyLong(), anyString(), anyString())).thenReturn(1);
        when(mapper.upsertDedupe(anyString(), anyString(), anyString(), anyLong(), anyBoolean())).thenReturn(1);
        when(mapper.selectByPath(owner.toString(), "note.txt")).thenReturn(Map.of("revision", 2L));

        TransactionSynchronizationManager.initSynchronization();
        files.publishUpload(owner, "", "note.txt", temp, "", false);
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("new");
        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);

        synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        TransactionSynchronizationManager.clearSynchronization();

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("old");
        CompletableFuture.runAsync(() -> files.mkdir(owner, "after-rollback"))
                .get(2, TimeUnit.SECONDS);
        assertThat(Files.isDirectory(ownerRoot.resolve("after-rollback"))).isTrue();
        assertNoMutationArtifacts();
    }

    @Test
    void malformedUtf8FallsBackToGbkWithoutReplacementCharacters() {
        byte[] gbk = "中文内容".getBytes(Charset.forName("GBK"));

        String decoded = MybatisFileStorageService.decodeTextPreview(gbk, false);

        assertThat(decoded).isEqualTo("中文内容").doesNotContain("\uFFFD");
    }

    @Test
    void truncatedUtf8CodePointIsDroppedWithoutFalseFallback() {
        byte[] complete = "abc中".getBytes(StandardCharsets.UTF_8);
        byte[] truncated = java.util.Arrays.copyOf(complete, complete.length - 1);

        String decoded = MybatisFileStorageService.decodeTextPreview(truncated, true);

        assertThat(decoded).isEqualTo("abc").doesNotContain("\uFFFD");
    }

    private void assertNoMutationArtifacts() throws Exception {
        try (var paths = Files.list(ownerRoot)) {
            assertThat(paths.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".copy-old.") || name.startsWith(".copy."))
                    .toList()).isEmpty();
        }
    }
}
