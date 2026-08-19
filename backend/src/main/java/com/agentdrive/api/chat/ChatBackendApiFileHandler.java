package com.agentdrive.api.chat;

import com.agentdrive.agent.BackendApiRequest;
import com.agentdrive.files.FileStorageService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@Profile("java-chat")
final class ChatBackendApiFileHandler implements BackendApiOperationHandler {
    private static final Set<String> OPERATIONS = Set.of(
            "INTERNAL write_text",
            "GET /api/v1/files",
            "GET /api/v1/files/info",
            "GET /api/v1/files/content",
            "GET /api/v1/files/dedupe",
            "GET /api/v1/files/trash",
            "POST /api/v1/files/mkdir",
            "POST /api/v1/files/rename",
            "POST /api/v1/files/move",
            "POST /api/v1/files/copy",
            "POST /api/v1/files/delete",
            "POST /api/v1/files/trash/restore",
            "POST /api/v1/files/trash/empty"
    );

    private final FileStorageService files;

    ChatBackendApiFileHandler(FileStorageService files) {
        this.files = files;
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public Map<String, Object> dispatch(String operation, BackendApiRequest request, UUID userId) {
        return switch (operation) {
            case "INTERNAL write_text" -> files.writeText(userId,
                    BackendApiParams.required(request, "path"),
                    BackendApiParams.parameter(request, "content", ""),
                    BackendApiParams.booleanParameter(request, "overwrite"));
            case "GET /api/v1/files" -> files.list(userId,
                    BackendApiParams.parameter(request, "path", ""),
                    BackendApiParams.parameter(request, "q", ""),
                    BackendApiParams.parameter(request, "mode", "name"));
            case "GET /api/v1/files/info" -> files.info(userId, BackendApiParams.required(request, "path"));
            case "GET /api/v1/files/content" -> files.content(userId,
                    BackendApiParams.required(request, "path"),
                    BackendApiParams.integerParameter(request, "max_bytes", 2 * 1024 * 1024));
            case "GET /api/v1/files/dedupe" -> files.dedupe(userId, BackendApiParams.required(request, "md5"));
            case "GET /api/v1/files/trash" -> files.listTrash(userId);
            case "POST /api/v1/files/mkdir" -> files.mkdir(userId, BackendApiParams.required(request, "path"));
            case "POST /api/v1/files/rename" -> files.rename(userId,
                    BackendApiParams.required(request, "src"), BackendApiParams.required(request, "dst"));
            case "POST /api/v1/files/move" -> files.move(userId,
                    BackendApiParams.required(request, "src"),
                    BackendApiParams.required(request, "dst_dir"),
                    BackendApiParams.booleanParameter(request, "overwrite"));
            case "POST /api/v1/files/copy" -> files.copy(userId,
                    BackendApiParams.required(request, "src"),
                    BackendApiParams.required(request, "dst"),
                    BackendApiParams.booleanParameter(request, "overwrite"));
            case "POST /api/v1/files/delete" -> files.deleteToTrash(userId,
                    BackendApiParams.required(request, "path"));
            case "POST /api/v1/files/trash/restore" -> files.restoreTrash(userId,
                    BackendApiParams.parameter(request, "trash_id", BackendApiParams.parameter(request, "path", "")));
            case "POST /api/v1/files/trash/empty" -> files.emptyTrash(userId);
            default -> throw new IllegalArgumentException("Unsupported file operation: " + operation);
        };
    }
}
