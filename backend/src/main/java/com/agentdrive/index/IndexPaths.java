package com.agentdrive.index;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Shared owner-relative path validation for direct index business APIs. */
public final class IndexPaths {
    public static final int MAX_FILES = 1000;
    private static final int MAX_PATH_LENGTH = 4096;
    private static final Set<String> INTERNAL_NAMES = Set.of(".index", ".trash", ".storage.lock");

    private IndexPaths() {
    }

    /** Normalize and validate a non-empty owner-relative path list. */
    public static List<String> normalize(List<?> raw) {
        if (raw == null || raw.isEmpty()) throw new IllegalArgumentException("files must contain at least one path");
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (Object value : raw) {
            if (!(value instanceof String)) throw new IllegalArgumentException("files must contain only strings");
            String path = ((String) value).trim().replace('\\', '/');
            validate(path);
            paths.add(path);
            if (paths.size() > MAX_FILES) throw new IllegalArgumentException("files may contain at most " + MAX_FILES + " paths");
        }
        if (paths.isEmpty()) throw new IllegalArgumentException("files must contain at least one path");
        return List.copyOf(paths);
    }

    private static void validate(String path) {
        if (path.isBlank()) throw new IllegalArgumentException("files must not contain blank paths");
        if (path.length() > MAX_PATH_LENGTH) throw new IllegalArgumentException("file path is too long");
        if (path.startsWith("/") || path.endsWith("/") || path.contains("//") || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("file path must be a relative file path");
        }
        for (String component : path.split("/", -1)) {
            if (component.isBlank() || component.equals(".") || component.equals("..")) {
                throw new IllegalArgumentException("file path contains an invalid component");
            }
            if (INTERNAL_NAMES.contains(component) || component.startsWith(".upload.")
                    || component.startsWith(".copy.") || component.startsWith(".copy-old.")) {
                throw new IllegalArgumentException("file path points to an internal path");
            }
        }
    }
}
