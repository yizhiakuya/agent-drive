package com.agentdrive.api.chat;

import com.agentdrive.agent.BackendApiRequest;
import com.agentdrive.index.IndexDomainService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Routes Agent index operations to the normal owner-scoped index domain service. */
@Component
@Profile("java-chat")
final class ChatBackendApiIndexHandler implements BackendApiOperationHandler {
    private static final Set<String> OPERATIONS = Set.of(
            "GET /api/v1/index",
            "GET /api/v1/index/file",
            "PUT /api/v1/index/file",
            "PUT /api/v1/index/vision",
            "PUT /api/v1/index/vectors",
            "DELETE /api/v1/index/vectors",
            "DELETE /api/v1/index/stale",
            "POST /api/v1/index/rebuild"
    );

    private final IndexDomainService index;

    ChatBackendApiIndexHandler(IndexDomainService index) {
        this.index = index;
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public Map<String, Object> dispatch(String operation, BackendApiRequest request, UUID userId) {
        return switch (operation) {
            case "GET /api/v1/index" -> index.overview(userId,
                    BackendApiParams.parameter(request, "prefix", ""),
                    Math.max(1, Math.min(1000, BackendApiParams.integerParameter(request, "limit", 200))));
            case "GET /api/v1/index/file" -> index.file(userId,
                    BackendApiParams.parameter(request, "path", ""));
            case "PUT /api/v1/index/file" -> index.indexFiles(userId, paths(request),
                    BackendApiParams.booleanParameter(request, "force"));
            case "PUT /api/v1/index/vision" -> index.indexVision(userId, paths(request),
                    BackendApiParams.booleanParameter(request, "force"));
            case "PUT /api/v1/index/vectors" -> index.vectorize(userId,
                    paths(request), BackendApiParams.booleanParameter(request, "force"),
                    Math.max(1, Math.min(1000, BackendApiParams.integerParameter(request, "limit", 64))));
            case "DELETE /api/v1/index/vectors" -> index.clearVectors(userId);
            case "DELETE /api/v1/index/stale" -> index.cleanup(userId);
            case "POST /api/v1/index/rebuild" -> index.rebuild(userId,
                    BackendApiParams.parameter(request, "prefix", ""));
            default -> throw new IllegalArgumentException("Unsupported index operation: " + operation);
        };
    }

    private List<String> paths(BackendApiRequest request) {
        Object value = request.body().get("paths");
        if (value == null) {
            // `files` is accepted as a compatibility alias for multi-file index calls.
            // The operation catalog still documents `paths` as the canonical field.
            value = request.body().get("files");
        }
        if (value == null) {
            String path = BackendApiParams.parameter(request, "path", "");
            if (path.isBlank()) throw new IllegalArgumentException("path, paths, or files is required");
            return List.of(path);
        }
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException("paths must be a list");
        return values.stream().map(valueItem -> {
            if (!(valueItem instanceof String path)) throw new IllegalArgumentException("paths must contain strings");
            return path;
        }).toList();
    }
}
