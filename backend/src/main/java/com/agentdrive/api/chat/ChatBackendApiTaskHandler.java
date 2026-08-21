package com.agentdrive.api.chat;

import com.agentdrive.agent.BackendApiRequest;
import com.agentdrive.tasks.IndexTaskPaths;
import com.agentdrive.tasks.TaskStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@Profile("java-chat")
final class ChatBackendApiTaskHandler implements BackendApiOperationHandler {
    private static final Set<String> OPERATIONS = Set.of(
            "GET /api/v1/tasks",
            "GET /api/v1/tasks/summary",
            "POST /api/v1/tasks/rebuild-index",
            "POST /api/v1/tasks/embed-index",
            "POST /api/v1/tasks/vision-index",
            "POST /api/v1/tasks/cleanup-index",
            "GET /api/v1/tasks/{task_id}",
            "POST /api/v1/tasks/{task_id}/cancel",
            "POST /api/v1/tasks/{task_id}/retry"
    );

    private final TaskStore tasks;

    ChatBackendApiTaskHandler(TaskStore tasks) {
        this.tasks = tasks;
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public Map<String, Object> dispatch(String operation, BackendApiRequest request, UUID userId) {
        return switch (operation) {
            case "GET /api/v1/tasks" -> taskList(request, userId);
            case "GET /api/v1/tasks/summary" -> tasks.overview(userId);
            case "POST /api/v1/tasks/rebuild-index" -> enqueueTask(request, userId,
                    "index.rebuild", "index", "index.rebuild:" + BackendApiParams.parameter(request, "prefix", "")
                            + ":" + BackendApiParams.booleanParameter(request, "force"));
            case "POST /api/v1/tasks/embed-index" -> enqueueEmbeddingTask(request, userId);
            case "POST /api/v1/tasks/vision-index" -> enqueueVisionTask(request, userId);
            case "POST /api/v1/tasks/cleanup-index" -> enqueueTask(request, userId,
                    "index.cleanup", "index", "index.cleanup");
            case "GET /api/v1/tasks/{task_id}" -> taskDetails(request, userId);
            case "POST /api/v1/tasks/{task_id}/cancel" -> taskTransition(request, userId, false);
            case "POST /api/v1/tasks/{task_id}/retry" -> taskTransition(request, userId, true);
            default -> throw new IllegalArgumentException("Unsupported task operation: " + operation);
        };
    }

    private Map<String, Object> taskList(BackendApiRequest request, UUID userId) {
        String rawStatus = BackendApiParams.parameter(request, "status", "");
        List<String> statuses = rawStatus.isBlank()
                ? List.of()
                : Arrays.stream(rawStatus.split(",")).filter(value -> !value.isBlank()).toList();
        boolean includeChildren = BackendApiParams.booleanParameter(request, "include_children");
        int limit = BackendApiParams.integerParameter(request, "limit", 50);
        int offset = BackendApiParams.integerParameter(request, "offset", 0);
        return Map.of(
                "items", tasks.list(userId, statuses, BackendApiParams.parameter(request, "task_type", ""),
                        includeChildren, limit, offset),
                "overview", tasks.overview(userId));
    }

    private Map<String, Object> enqueueTask(BackendApiRequest request, UUID userId,
                                             String type, String lane, String dedupeKey) {
        Map<String, Object> payload = Map.of(
                "prefix", BackendApiParams.parameter(request, "prefix", ""),
                "force", BackendApiParams.booleanParameter(request, "force"));
        TaskStore.EnqueueResult result = tasks.enqueue(userId, type, lane, payload, dedupeKey, "agent", null);
        return Map.of("queued", result.created(), "task", result.task());
    }

    private Map<String, Object> enqueueEmbeddingTask(BackendApiRequest request, UUID userId) {
        Object rawFiles = request.body().get("files");
        if (!(rawFiles instanceof List<?> files)) return Map.of("ok", false, "error", "files_must_be_list");
        List<String> paths;
        try {
            paths = IndexTaskPaths.normalize(files);
        } catch (IllegalArgumentException error) {
            return Map.of("ok", false, "error", "invalid_files", "detail", error.getMessage());
        }
        boolean force = BackendApiParams.booleanParameter(request, "force");
        TaskStore.EnqueueResult result = tasks.enqueue(userId, "index.embed", "index",
                Map.of("files", paths, "force", force), IndexTaskPaths.dedupeKey(paths, force), "agent", null);
        return Map.of("queued", result.created(), "task", result.task());
    }

    private Map<String, Object> enqueueVisionTask(BackendApiRequest request, UUID userId) {
        Object rawFiles = request.body().get("files");
        if (!(rawFiles instanceof List<?> files) || files.isEmpty() || files.size() > 100) {
            return Map.of("ok", false, "error", "vision_files_must_contain_1_to_100_paths");
        }
        List<String> paths;
        try {
            paths = IndexTaskPaths.normalize(files);
        } catch (IllegalArgumentException error) {
            return Map.of("ok", false, "error", "invalid_files", "detail", error.getMessage());
        }
        boolean force = BackendApiParams.booleanParameter(request, "force");
        TaskStore.EnqueueResult result = tasks.enqueue(userId, "index.vision", "index",
                Map.of("files", paths, "force", force),
                "index.vision:" + IndexTaskPaths.dedupeKey(paths, force), "agent", null);
        return Map.of("queued", result.created(), "task", result.task());
    }

    private Map<String, Object> taskDetails(BackendApiRequest request, UUID userId) {
        UUID taskId = UUID.fromString(BackendApiParams.requiredPath(request, "task_id"));
        Map<String, Object> task = tasks.get(userId, taskId);
        if (task == null) return Map.of("ok", false, "error", "task_not_found");
        return Map.of("task", task, "children", tasks.childSummary(userId, taskId));
    }

    private Map<String, Object> taskTransition(BackendApiRequest request, UUID userId, boolean retry) {
        UUID taskId = UUID.fromString(BackendApiParams.requiredPath(request, "task_id"));
        TaskStore.TransitionResult result = retry ? tasks.retry(userId, taskId) : tasks.cancel(userId, taskId);
        if (result.task() == null) return Map.of("ok", false, "error", "task_not_found");
        if (retry && !result.changed()) return Map.of("ok", false, "error", "task_not_retryable");
        return Map.of("task", result.task(), "changed", result.changed());
    }
}
