package com.agentdrive.infrastructure.persistence;

import com.agentdrive.files.FileStorageException;
import com.agentdrive.infrastructure.persistence.mapper.FileMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("java-files")
@EnabledIfEnvironmentVariable(named = "AGENT_DRIVE_JDBC_TEST_URL", matches = ".+")
class MybatisFileStorageServiceIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FileMapper mapper;

    private final UUID owner = UUID.randomUUID();
    private final UUID otherOwner = UUID.randomUUID();
    private Path root;
    private MybatisFileStorageService files;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("agent-drive-java-files-");
        files = new MybatisFileStorageService(mapper, root, 10 * 1024 * 1024L);
        jdbc.update("INSERT INTO users(id, username, password_hash) VALUES (?::uuid, ?, ?)",
                owner, "file-integration-" + owner, "test-hash");
        jdbc.update("INSERT INTO users(id, username, password_hash) VALUES (?::uuid, ?, ?)",
                otherOwner, "file-integration-" + otherOwner, "test-hash");
    }

    @AfterEach
    void tearDown() throws Exception {
        jdbc.update("DELETE FROM users WHERE id IN (?::uuid, ?::uuid)", owner, otherOwner);
        if (root != null) {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void uploadsRevisionDedupeOwnerIsolationAndTrashRestore() throws Exception {
        String alphaMd5 = "2c1743a391305fbf367df8e4f069f9f9";
        Map<String, Object> first = upload(owner, "docs", "note.txt", "alpha", alphaMd5, false);
        assertThat(first.get("uploaded")).extracting("path").isEqualTo("docs/note.txt");

        Map<String, Object> duplicateName = upload(owner, "docs", "note.txt", "beta", "987bcab01b929eb2c07877b224215c92", true);
        assertThat(duplicateName.get("uploaded")).extracting("path").isEqualTo("docs/note-2.txt");

        assertThat(files.dedupe(owner, alphaMd5).get("uploaded")).extracting("path").isEqualTo("docs/note.txt");
        Map<String, Object> other = upload(otherOwner, "docs", "note.txt", "other", "", true);
        assertThat(((Map<?, ?>) other.get("uploaded")).containsKey("deduped")).isFalse();

        Map<String, Object> trash = files.deleteToTrash(owner, "docs/note.txt");
        String trashId = String.valueOf(trash.get("trash_id"));
        assertThat(files.listTrash(owner).get("items")).asList().anySatisfy(item ->
                assertThat(String.valueOf(((Map<?, ?>) item).get("trash_id"))).isEqualTo(trashId));
        files.restoreTrash(owner, trashId);
        assertThat(Files.exists(root.resolve(owner.toString()).resolve("docs/note.txt"))).isTrue();

        assertThat(Files.readString(files.fileForRead(otherOwner, "docs/note.txt"))).isEqualTo("other");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM file_revisions r JOIN files f ON f.id = r.file_id WHERE f.user_id = ?::uuid",
                Integer.class, owner)).isEqualTo(2);
    }

    @Test
    void rejectsTraversalAndDeclaredHashMismatch() throws Exception {
        assertThatThrownBy(() -> files.mkdir(owner, "../escape"))
                .isInstanceOf(FileStorageException.class)
                .satisfies(error -> assertThat(((FileStorageException) error).status()).isEqualTo(403));

        Path temp = files.createUploadTemp();
        Files.writeString(temp, "alpha");
        try {
            assertThatThrownBy(() -> files.publishUpload(owner, "", "bad.txt", temp,
                    "00000000000000000000000000000000", false))
                    .isInstanceOf(FileStorageException.class)
                    .satisfies(error -> assertThat(((FileStorageException) error).status()).isEqualTo(400));
        } finally {
            files.discardTemp(temp);
        }
    }

    @Test
    void cleanupTrashOnlyRemovesEntriesOlderThanRetentionPeriod() throws Exception {
        String md5 = "2c1743a391305fbf367df8e4f069f9f9";
        Map<String, Object> uploaded = upload(owner, "", "old.txt", "alpha", md5, false);
        Map<String, Object> trash = files.deleteToTrash(owner, String.valueOf(((Map<?, ?>) uploaded.get("uploaded")).get("path")));
        jdbc.update("UPDATE trash_entries SET deleted_at = now() - interval '31 days' WHERE trash_id = ?::uuid",
                trash.get("trash_id"));

        Map<String, Object> result = files.cleanupTrash(owner, 30);

        assertThat(result).containsEntry("removed", 1);
        assertThat(files.listTrash(owner).get("items")).asList().isEmpty();
        assertThat(Files.exists(root.resolve(owner.toString()).resolve("old.txt"))).isFalse();
    }

    @Test
    void copiesDirectoryThroughHiddenStaging() throws Exception {
        upload(owner, "source", "root.txt", "alpha", "2c1743a391305fbf367df8e4f069f9f9", false);
        upload(owner, "source/nested", "note.txt", "beta", "987bcab01b929eb2c07877b224215c92", false);

        files.copy(owner, "source", "archive", false);

        Path ownerRoot = root.resolve(owner.toString());
        assertThat(Files.readString(ownerRoot.resolve("archive/root.txt"))).isEqualTo("alpha");
        assertThat(Files.readString(ownerRoot.resolve("archive/nested/note.txt"))).isEqualTo("beta");
        assertThat(hiddenCopyArtifacts()).isEmpty();
        assertThat(visibleNames()).noneMatch(name -> name.startsWith(".copy") || name.startsWith(".copy-old"));
    }

    @Test
    void overwriteCopyReplacesCompatibleDirectoryWithoutExposingPartialTree() throws Exception {
        upload(owner, "source", "fresh.txt", "alpha", "2c1743a391305fbf367df8e4f069f9f9", false);
        upload(owner, "target", "stale.txt", "beta", "987bcab01b929eb2c07877b224215c92", false);

        files.copy(owner, "source", "target", true);

        Path ownerRoot = root.resolve(owner.toString());
        assertThat(Files.readString(ownerRoot.resolve("target/fresh.txt"))).isEqualTo("alpha");
        assertThat(Files.exists(ownerRoot.resolve("target/stale.txt"))).isFalse();
        assertThat(hiddenCopyArtifacts()).isEmpty();
    }

    @Test
    void rejectsCopyTypeConflictsAndOwnerRootTarget() throws Exception {
        upload(owner, "", "source.txt", "alpha", "2c1743a391305fbf367df8e4f069f9f9", false);
        files.mkdir(owner, "target-dir");
        upload(owner, "source-dir", "item.txt", "beta", "987bcab01b929eb2c07877b224215c92", false);
        upload(owner, "", "target-file.txt", "gamma", "05b048d7242cb7b8b57cfa3b1d65ecea", false);

        assertThatThrownBy(() -> files.copy(owner, "source.txt", "target-dir", true))
                .isInstanceOf(FileStorageException.class)
                .satisfies(error -> assertThat(((FileStorageException) error).status()).isEqualTo(409));
        assertThatThrownBy(() -> files.copy(owner, "source-dir", "target-file.txt", true))
                .isInstanceOf(FileStorageException.class)
                .satisfies(error -> assertThat(((FileStorageException) error).status()).isEqualTo(409));
        assertThatThrownBy(() -> files.copy(owner, "source.txt", "", true))
                .isInstanceOf(FileStorageException.class)
                .satisfies(error -> assertThat(((FileStorageException) error).status()).isEqualTo(400));
    }

    @Test
    void failedDirectoryCopyLeavesExistingTargetIntact() throws Exception {
        upload(owner, "source", "ok.txt", "alpha", "2c1743a391305fbf367df8e4f069f9f9", false);
        upload(owner, "target", "keep.txt", "beta", "987bcab01b929eb2c07877b224215c92", false);
        Path link = root.resolve(owner.toString()).resolve("source/broken-link");
        try {
            Files.createSymbolicLink(link, Path.of("missing-target"));
        } catch (UnsupportedOperationException | java.io.IOException error) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable: " + error.getMessage());
        }

        assertThatThrownBy(() -> files.copy(owner, "source", "target", true))
                .isInstanceOf(FileStorageException.class)
                .satisfies(error -> assertThat(((FileStorageException) error).status()).isEqualTo(403));
        Path ownerRoot = root.resolve(owner.toString());
        assertThat(Files.readString(ownerRoot.resolve("target/keep.txt"))).isEqualTo("beta");
        assertThat(Files.exists(ownerRoot.resolve("target/ok.txt"))).isFalse();
        assertThat(hiddenCopyArtifacts()).isEmpty();
    }

    private List<String> visibleNames() throws Exception {
        try (var paths = Files.list(root.resolve(owner.toString()))) {
            return paths.map(path -> path.getFileName().toString()).toList();
        }
    }

    private List<String> hiddenCopyArtifacts() throws Exception {
        try (var paths = Files.list(root.resolve(owner.toString()))) {
            return paths.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".copy") || name.startsWith(".copy-old"))
                    .toList();
        }
    }

    private Map<String, Object> upload(UUID userId, String directory, String filename,
                                       String content, String md5, boolean noclobber) throws Exception {
        Path temp = files.createUploadTemp();
        Files.writeString(temp, content);
        try {
            return files.publishUpload(userId, directory, filename, temp, md5, noclobber);
        } finally {
            files.discardTemp(temp);
        }
    }
}
