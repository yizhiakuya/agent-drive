package com.agentdrive.fileservice;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * File Service 的最小内容读取用例。
 *
 * <p>服务只拥有自己的 owner 分区，不接触主 API 数据库。路径按 POSIX 相对语义解析，
 * 拒绝越界、符号链接、内部目录和非普通文件；返回的 bytes 只存在于当前响应。</p>
 */
@Service
public final class FileContentService {
    private static final int MAX_MANIFEST_ENTRIES = 100_000;
    private static final java.util.regex.Pattern MD5 = java.util.regex.Pattern.compile("[0-9a-fA-F]{32}");
    private final FileServiceProperties properties;

    /** 创建文件内容服务并确保存储根存在。 */
    public FileContentService(FileServiceProperties properties) {
        this.properties = properties;
        try {
            Files.createDirectories(properties.rootPath());
        } catch (IOException error) {
            throw new IllegalStateException("file service storage root is unavailable", error);
        }
    }

    /** 返回不包含本地路径和令牌的服务 readiness。 */
    public Map<String, Object> ready() {
        boolean available = Files.isDirectory(properties.rootPath(), LinkOption.NOFOLLOW_LINKS)
                && Files.isWritable(properties.rootPath());
        return Map.of("ready", !properties.internalToken().isBlank() && available,
                "service", "file", "storage_available", available);
    }

    /** 读取 owner 普通文件并返回临时 Base64 内容和校验摘要。 */
    public Map<String, Object> read(ReadRequest request) {
        UUID owner = parseOwner(request.ownerId());
        String path = normalizeRelative(request.path());
        if (request.maxBytes() != null && request.maxBytes() <= 0) {
            throw new IllegalArgumentException("max_bytes is invalid");
        }
        long limit = request.maxBytes() == null ? properties.maxReadBytes()
                : Math.min(properties.maxReadBytes(), request.maxBytes());
        Path file = resolve(owner, path);
        try {
            long size = Files.size(file);
            if (size > limit) throw new IllegalArgumentException("file_too_large");
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > limit) throw new IllegalArgumentException("file_too_large");
            String md5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
            return Map.of("ok", true, "owner_id", owner.toString(), "path", path,
                    "size_bytes", bytes.length, "content_md5", md5,
                    "data", Base64.getEncoder().encodeToString(bytes));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("file_read_failed", error);
        }
    }

    /**
     * 生成 owner 文件迁移校验清单。
     * 清单只包含可见普通文件的相对路径、大小和 MD5，不返回文件内容；内部目录、符号链接和目录本身不会进入清单。
     *
     * @param ownerId owner UUID 文本
     * @return 可排序、可比对的文件清单和总大小
     */
    public Map<String, Object> manifest(String ownerId) {
        UUID owner = parseOwner(ownerId);
        Path ownerRoot = properties.rootPath().resolve(owner.toString()).normalize();
        if (!Files.isDirectory(ownerRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("file_not_found");
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        long totalBytes = 0;
        try (var paths = Files.walk(ownerRoot)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (path.equals(ownerRoot) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || hasSymlinkComponent(ownerRoot, path) || isInternalPath(ownerRoot, path)) {
                    continue;
                }
                if (entries.size() >= MAX_MANIFEST_ENTRIES) {
                    throw new IllegalArgumentException("manifest_too_large");
                }
                long size = Files.size(path);
                String relative = ownerRoot.relativize(path).toString().replace('\\', '/');
                entries.add(Map.of("path", relative, "size_bytes", size, "content_md5", md5(path)));
                totalBytes += size;
            }
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (IOException error) {
            throw new IllegalStateException("manifest_failed", error);
        }
        return Map.of("ok", true, "owner_id", owner.toString(), "entries", List.copyOf(entries),
                "file_count", entries.size(), "total_size_bytes", totalBytes);
    }

    /**
     * 原子写入一个 owner 普通文件的镜像内容。
     * 请求方必须提供 revision 和 MD5；File Service 会重新计算 MD5，拒绝目录、内部路径和过大内容。
     *
     * @param request 受内部 token 保护的镜像请求
     * @return 写入后的路径、revision、大小和 MD5
     */
    public Map<String, Object> mirror(MirrorRequest request) {
        UUID owner = parseOwner(request.ownerId());
        if (request.revision() < 1 || !MD5.matcher(request.contentMd5()).matches()) {
            throw new IllegalArgumentException("mirror metadata is invalid");
        }
        String path = normalizeRelative(request.path());
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(request.data());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("mirror data is not valid Base64", error);
        }
        if (bytes.length == 0 || bytes.length > properties.maxReadBytes()) {
            throw new IllegalArgumentException("file_too_large");
        }
        String actual = HexFormat.of().formatHex(md5Bytes(bytes));
        if (!actual.equalsIgnoreCase(request.contentMd5())) {
            throw new IllegalArgumentException("content_md5 does not match data");
        }
        Path ownerRoot = properties.rootPath().resolve(owner.toString()).normalize();
        Path target = ownerRoot.resolve(path).normalize();
        if (!target.startsWith(ownerRoot) || (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))) {
            throw new IllegalArgumentException("mirror target is invalid");
        }
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".mirror.", ".tmp");
            try {
                Files.write(temp, bytes);
                try {
                    Files.move(temp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            return Map.of("ok", true, "owner_id", owner.toString(), "path", path,
                    "revision", request.revision(), "size_bytes", bytes.length, "content_md5", actual);
        } catch (IOException error) {
            throw new IllegalStateException("mirror_write_failed", error);
        }
    }

    /** 删除一个 owner 镜像文件；目录和内部路径不会被删除。 */
    public Map<String, Object> deleteMirror(String ownerId, String path) {
        UUID owner = parseOwner(ownerId);
        String normalized = normalizeRelative(path);
        Path target = resolve(owner, normalized);
        try {
            Files.deleteIfExists(target);
            return Map.of("ok", true, "owner_id", owner.toString(), "path", normalized, "deleted", true);
        } catch (IOException error) {
            throw new IllegalStateException("mirror_delete_failed", error);
        }
    }

    /** 删除一个 owner 文件或目录镜像树。 */
    public Map<String, Object> deleteMirrorTree(String ownerId, String path) {
        UUID owner = parseOwner(ownerId);
        String normalized = normalizeRelative(path);
        Path target = resolveExisting(properties.rootPath().resolve(owner.toString()).normalize(), normalized);
        try {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                try (var paths = Files.walk(target).sorted(java.util.Comparator.reverseOrder())) {
                    for (Path item : paths.toList()) Files.deleteIfExists(item);
                }
            } else {
                Files.deleteIfExists(target);
            }
            return Map.of("ok", true, "owner_id", owner.toString(), "path", normalized, "deleted", true);
        } catch (IOException error) {
            throw new IllegalStateException("mirror_tree_delete_failed", error);
        }
    }

    /** 在 owner 存储内原子移动文件或目录镜像。 */
    public Map<String, Object> moveMirror(MirrorPathRequest request) {
        UUID owner = parseOwner(request.ownerId());
        String source = normalizeRelative(request.source());
        String destination = normalizeRelative(request.destination());
        Path ownerRoot = properties.rootPath().resolve(owner.toString()).normalize();
        Path sourcePath = resolveExisting(ownerRoot, source);
        Path target = ownerRoot.resolve(destination).normalize();
        if (sourcePath.equals(target) || !target.startsWith(ownerRoot)
                || (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !request.overwrite())) {
            throw new IllegalArgumentException("mirror move target is invalid or already exists");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)
                != Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("mirror move type mismatch");
        }
        try {
            Files.createDirectories(target.getParent());
            try {
                Files.move(sourcePath, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(sourcePath, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return Map.of("ok", true, "owner_id", owner.toString(), "source", source,
                    "destination", destination, "moved", true);
        } catch (IOException error) {
            throw new IllegalStateException("mirror_move_failed", error);
        }
    }

    /** 在 owner 存储内复制文件或目录镜像。 */
    public Map<String, Object> copyMirror(MirrorPathRequest request) {
        UUID owner = parseOwner(request.ownerId());
        String source = normalizeRelative(request.source());
        String destination = normalizeRelative(request.destination());
        Path ownerRoot = properties.rootPath().resolve(owner.toString()).normalize();
        Path sourcePath = resolveExisting(ownerRoot, source);
        Path target = ownerRoot.resolve(destination).normalize();
        if (sourcePath.equals(target) || !target.startsWith(ownerRoot)
                || (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !request.overwrite())) {
            throw new IllegalArgumentException("mirror copy target is invalid or already exists");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)
                != Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("mirror copy type mismatch");
        }
        try {
            if (Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                copyDirectory(sourcePath, target);
            } else {
                Files.createDirectories(target.getParent());
                Files.copy(sourcePath, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        LinkOption.NOFOLLOW_LINKS);
            }
            return Map.of("ok", true, "owner_id", owner.toString(), "source", source,
                    "destination", destination, "copied", true);
        } catch (IOException error) {
            throw new IllegalStateException("mirror_copy_failed", error);
        }
    }

    private Path resolve(UUID owner, String path) {
        Path ownerRoot = properties.rootPath().resolve(owner.toString()).normalize();
        Path target = resolveExisting(ownerRoot, path);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("file_not_found");
        }
        return target;
    }

    private Path resolveExisting(Path ownerRoot, String path) {
        if (!Files.isDirectory(ownerRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("file_not_found");
        }
        Path target = ownerRoot.resolve(path).normalize();
        if (!target.startsWith(ownerRoot) || !Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                || hasSymlinkComponent(ownerRoot, target)) {
            throw new IllegalArgumentException("file_not_found");
        }
        return target;
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            try (var children = Files.list(target)) {
                if (children.findAny().isPresent()) {
                    throw new IllegalArgumentException("mirror copy target directory is not empty");
                }
            }
        } else {
            Files.createDirectories(target);
        }
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("mirror copy symlink is invalid");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination, LinkOption.NOFOLLOW_LINKS);
                }
            }
        }
    }

    private boolean hasSymlinkComponent(Path ownerRoot, Path target) {
        Path relative = ownerRoot.relativize(target);
        Path current = ownerRoot;
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) return true;
        }
        return false;
    }

    private boolean isInternalPath(Path ownerRoot, Path target) {
        for (Path part : ownerRoot.relativize(target)) {
            String name = part.toString();
            if (name.equals(".index") || name.equals(".trash") || name.equals(".versions")
                    || name.startsWith(".upload") || name.startsWith(".copy")) {
                return true;
            }
        }
        return false;
    }

    private String md5(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("MD5 is unavailable", error);
        }
    }

    private byte[] md5Bytes(byte[] bytes) {
        try {
            return MessageDigest.getInstance("MD5").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("MD5 is unavailable", error);
        }
    }

    private UUID parseOwner(String value) {
        try {
            return UUID.fromString(value == null ? "" : value.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("owner_id is invalid", error);
        }
    }

    private String normalizeRelative(String value) {
        String path = value == null ? "" : value.trim().replace('\\', '/');
        if (path.isBlank() || path.startsWith("/") || path.matches("^[A-Za-z]:.*")
                || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("path is invalid");
        }
        Path normalized = Path.of(path).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")
                || normalized.toString().equals(".")) {
            throw new IllegalArgumentException("path is invalid");
        }
        String result = normalized.toString().replace('\\', '/');
        for (String part : result.split("/")) {
            if (part.equals(".index") || part.equals(".trash") || part.equals(".versions")
                    || part.startsWith(".upload") || part.startsWith(".copy")) {
                throw new IllegalArgumentException("path is invalid");
            }
        }
        return result;
    }

    /** 受限文件读取请求。 */
    public record ReadRequest(String ownerId, String path, Long maxBytes) {
    }
}
