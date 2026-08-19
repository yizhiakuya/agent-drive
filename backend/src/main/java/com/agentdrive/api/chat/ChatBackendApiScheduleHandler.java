package com.agentdrive.api.chat;

import com.agentdrive.agent.BackendApiRequest;
import com.agentdrive.tasks.ScheduleStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@Profile("java-chat")
final class ChatBackendApiScheduleHandler implements BackendApiOperationHandler {
    private static final Set<String> OPERATIONS = Set.of(
            "GET /api/v1/schedules",
            "PUT /api/v1/schedules/{name}",
            "DELETE /api/v1/schedules/{name}"
    );

    private final ScheduleStore schedules;

    ChatBackendApiScheduleHandler(ScheduleStore schedules) {
        this.schedules = schedules;
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public Map<String, Object> dispatch(String operation, BackendApiRequest request, UUID userId) {
        return switch (operation) {
            case "GET /api/v1/schedules" -> Map.of("schedules", schedules.list(userId));
            case "PUT /api/v1/schedules/{name}" -> upsert(request, userId);
            case "DELETE /api/v1/schedules/{name}" -> delete(request, userId);
            default -> throw new IllegalArgumentException("Unsupported schedule operation: " + operation);
        };
    }

    private Map<String, Object> upsert(BackendApiRequest request, UUID userId) {
        Map<String, Object> body = request.body();
        Object payload = body.get("payload");
        return Map.of("schedule", schedules.upsert(
                userId,
                BackendApiParams.requiredPath(request, "name"),
                BackendApiParams.parameter(request, "cron", null),
                BackendApiParams.parameter(request, "schedule_kind", "cron"),
                BackendApiParams.parameter(request, "schedule_value", BackendApiParams.parameter(request, "cron", null)),
                BackendApiParams.parameter(request, "task_type", null),
                BackendApiParams.parameter(request, "lane", "default"),
                payload instanceof Map<?, ?> map ? BackendApiParams.castMap(map) : Map.of(),
                BackendApiParams.booleanParameter(request, "enabled"),
                BackendApiParams.integerParameter(request, "priority", 0),
                BackendApiParams.integerParameter(request, "max_attempts", 3),
                BackendApiParams.parameter(request, "timezone", "UTC")));
    }

    private Map<String, Object> delete(BackendApiRequest request, UUID userId) {
        String name = BackendApiParams.requiredPath(request, "name");
        return Map.of("deleted", name, "ok", schedules.delete(userId, name));
    }
}
