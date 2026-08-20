package com.agentdrive.tasks;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 统一校验文件列表索引任务的路径，并生成稳定的任务去重键。
 * 只接受 owner 存储根下的相对用户路径，规范化分隔符、去除重复项，最多保留
 * {@value #MAX_FILES} 条；同时拒绝 traversal 和内部 staging 目录。
 */
public final class IndexTaskPaths {
    public static final int MAX_FILES = 1000;
    private static final int MAX_PATH_LENGTH = 4096;
    private static final Set<String> INTERNAL_NAMES = Set.of(
            ".index", ".trash", ".storage.lock"
    );

    /** 工具类不允许实例化。 */
    private IndexTaskPaths() {
    }

    /**
     * 将请求中的文件列表规范化为去重后的相对路径，并限制最多 {@value #MAX_FILES} 项。
     * @param raw 请求体中的原始列表；每一项必须是字符串。
     * @return 保留首次出现顺序的不可变路径列表。
     * @throws IllegalArgumentException 列表为空、元素不是字符串、路径越界或指向内部目录时抛出。
     */
    public static List<String> normalize(List<?> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("files must contain at least one path");
        }
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (Object value : raw) {
            if (!(value instanceof String)) {
                throw new IllegalArgumentException("files must contain only strings");
            }
            String path = ((String) value).trim().replace('\\', '/');
            validate(path);
            paths.add(path);
            if (paths.size() > MAX_FILES) {
                throw new IllegalArgumentException("files may contain at most " + MAX_FILES + " paths");
            }
        }
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("files must contain at least one path");
        }
        return List.copyOf(paths);
    }

    /**
     * 按排序后的路径和 force 模式计算 SHA-256 去重键，使输入顺序不同的同一任务仍能合并。
     * @param paths 已规范化的用户相对文件路径。
     * @param force 是否把强制重建模式纳入去重范围。
     * @return 可直接用于任务 partial unique index 的去重键。
     */
    public static String dedupeKey(List<String> paths, boolean force) {
        List<String> sorted = new ArrayList<>(paths);
        sorted.sort(String::compareTo);
        String input = (force ? "force\n" : "current\n") + String.join("\n", sorted);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return "index.embed:" + HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("cannot create index task dedupe key", error);
        }
    }

    /**
     * 校验单个路径的相对性、组件合法性和内部目录边界。
     * @param path 已将反斜杠转换为斜杠的用户路径。
     * @throws IllegalArgumentException 路径为空、越界、含 traversal 或命中内部名称时抛出。
     */
    private static void validate(String path) {
        if (path.isBlank()) {
            throw new IllegalArgumentException("files must not contain blank paths");
        }
        if (path.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("file path is too long");
        }
        if (path.startsWith("/") || path.endsWith("/") || path.contains("//") || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("file path must be a relative file path");
        }
        for (String component : path.split("/", -1)) {
            if (component.isBlank() || component.equals(".") || component.equals("..")) {
                throw new IllegalArgumentException("file path contains an invalid component");
            }
            if (INTERNAL_NAMES.contains(component)
                    || component.startsWith(".upload.")
                    || component.startsWith(".copy.")
                    || component.startsWith(".copy-old.")) {
                throw new IllegalArgumentException("file path points to an internal path");
            }
        }
    }
}
