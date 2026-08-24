package com.agentdrive.fileservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 File Service 的 owner 隔离、路径安全和原始 bytes 契约。 */
class FileContentServiceTest {
    @Test
    void readsOwnerFileWithoutChangingBytes(@TempDir Path root) throws Exception {
        UUID owner = UUID.randomUUID();
        Path file = root.resolve(owner.toString()).resolve("photos").resolve("a.bin");
        Files.createDirectories(file.getParent());
        byte[] bytes = new byte[]{0, 1, 2, 127, (byte) 255};
        Files.write(file, bytes);

        FileContentService service = new FileContentService(
                new FileServiceProperties("internal", root.toString(), 1024L));
        var result = service.read(new FileContentService.ReadRequest(owner.toString(), "photos/a.bin", 100L));

        assertThat(result).containsEntry("ok", true);
        assertThat(result.get("data")).isEqualTo(Base64.getEncoder().encodeToString(bytes));
        assertThat(result.get("size_bytes")).isEqualTo(bytes.length);
        assertThat(result.get("content_md5")).isEqualTo("e555b7e025cdf6f435c4c400f4df7ef4");
    }

    @Test
    void rejectsTraversalAndInternalPaths(@TempDir Path root) {
        FileContentService service = new FileContentService(
                new FileServiceProperties("internal", root.toString(), 1024L));
        String owner = UUID.randomUUID().toString();

        assertThatThrownBy(() -> service.read(new FileContentService.ReadRequest(owner, "../secret", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("path is invalid");
        assertThatThrownBy(() -> service.read(new FileContentService.ReadRequest(owner, ".versions/x", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("path is invalid");
    }

    @Test
    void rejectsSymlinkComponents(@TempDir Path root) throws Exception {
        UUID owner = UUID.randomUUID();
        Path ownerRoot = root.resolve(owner.toString());
        Files.createDirectories(ownerRoot);
        Path outside = root.resolve("outside.txt");
        Files.writeString(outside, "secret");
        try {
            Files.createSymbolicLink(ownerRoot.resolve("linked.txt"), outside);
        } catch (IOException | UnsupportedOperationException error) {
            // Windows CI may not grant the privilege required to create symlinks.
            return;
        }

        FileContentService service = new FileContentService(
                new FileServiceProperties("internal", root.toString(), 1024L));

        assertThatThrownBy(() -> service.read(new FileContentService.ReadRequest(owner.toString(), "linked.txt", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("file_not_found");
    }

    @Test
    void createsMigrationManifestWithoutIncludingInternalFiles(@TempDir Path root) throws Exception {
        UUID owner = UUID.randomUUID();
        Path ownerRoot = root.resolve(owner.toString());
        Files.createDirectories(ownerRoot.resolve("docs"));
        Files.createDirectories(ownerRoot.resolve(".versions"));
        Files.writeString(ownerRoot.resolve("docs/a.txt"), "hello");
        Files.writeString(ownerRoot.resolve(".versions/old.txt"), "old");

        FileContentService service = new FileContentService(
                new FileServiceProperties("internal", root.toString(), 1024L));

        var result = service.manifest(owner.toString());

        assertThat(result).containsEntry("file_count", 1);
        assertThat(result.get("entries").toString()).contains("docs/a.txt").doesNotContain(".versions");
    }

    @Test
    void mirrorsBytesOnlyWhenMd5Matches(@TempDir Path root) throws Exception {
        UUID owner = UUID.randomUUID();
        FileContentService service = new FileContentService(
                new FileServiceProperties("internal", root.toString(), 1024L));
        byte[] bytes = "mirror".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String data = Base64.getEncoder().encodeToString(bytes);
        String md5 = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("MD5").digest(bytes));

        var result = service.mirror(new MirrorRequest(owner.toString(), "docs/mirror.txt", 3, md5, data));

        assertThat(result).containsEntry("revision", 3L);
        assertThat(Files.readString(root.resolve(owner.toString()).resolve("docs/mirror.txt")))
                .isEqualTo("mirror");
    }

    @Test
    void movesAndCopiesMirrorPaths(@TempDir Path root) throws Exception {
        UUID owner = UUID.randomUUID();
        Path source = root.resolve(owner.toString()).resolve("source.txt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "content");
        FileContentService service = new FileContentService(
                new FileServiceProperties("internal", root.toString(), 1024L));

        service.moveMirror(new MirrorPathRequest(owner.toString(), "source.txt", "moved.txt", false));
        service.copyMirror(new MirrorPathRequest(owner.toString(), "moved.txt", "copy.txt", false));

        assertThat(Files.exists(root.resolve(owner.toString()).resolve("source.txt"))).isFalse();
        assertThat(Files.readString(root.resolve(owner.toString()).resolve("moved.txt"))).isEqualTo("content");
        assertThat(Files.readString(root.resolve(owner.toString()).resolve("copy.txt"))).isEqualTo("content");
    }

    @Test
    void deletesMirrorDirectoryTree(@TempDir Path root) throws Exception {
        UUID owner = UUID.randomUUID();
        Path folder = root.resolve(owner.toString()).resolve("folder");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("a.txt"), "a");
        FileContentService service = new FileContentService(
                new FileServiceProperties("internal", root.toString(), 1024L));

        service.deleteMirrorTree(owner.toString(), "folder");

        assertThat(Files.exists(folder)).isFalse();
    }
}
