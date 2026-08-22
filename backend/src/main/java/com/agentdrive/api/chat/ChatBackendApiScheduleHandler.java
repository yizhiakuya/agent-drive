package com.agentdrive.api.chat;

import com.agentdrive.agent.BackendApiRequest;
import com.agentdrive.tasks.ScheduleStore;
import com.agentdrive.tasks.TaskStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

@Component
@Profile("java-chat")
final class ChatBackendApiScheduleHandler implements BackendApiOperationHandler {
    private static final Set<String> OPERATIONS = Set.of(
            "GET /api/v1/schedules",
            "PUT /api/v1/schedules/{name}",
            "DELETE /api/v1/schedules/{name}",
            "POST /api/v1/schedules/{name}/run"
    );

    private final ScheduleStore schedules;
    private final TaskStore tasks;

    @Autowired
    ChatBackendApiScheduleHandler(ScheduleStore schedules, TaskStore tasks) {
        this.schedules = schedules;
        this.tasks = tasks;
    }

    ChatBackendApiScheduleHandler(ScheduleStore schedules) {
        this(schedules, null);
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
            case "POST /api/v1/schedules/{name}/run" -> run(request, userId);
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

    private Map<String, Object> run(BackendApiRequest request, UUID userId) {
        if (tasks == null) throw new IllegalStateException("task store unavailable");
        String name = BackendApiParams.requiredPath(request, "name");
        Map<String, Object> schedule = schedules.list(userId).stream()
                .filter(item -> name.equals(String.valueOf(item.get("name"))))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("schedule not found"));
        Map<String, Object> payload = new LinkedHashMap<>();
        Object configured = schedule.get("payload");
        if (configured instanceof Map<?, ?> values) {
            values.forEach((key, value) -> {
                if (key != null) payload.put(String.valueOf(key), value);
            });
        }
        payload.put("schedule_name", name);
        TaskStore.EnqueueResult result = tasks.enqueue(
                userId, "automation.run", "automation", payload,
                "automation.manual:" + name + ":" + UUID.randomUUID(), "agent", null,
                integer(schedule.get("priority"), 0), Math.max(1, integer(schedule.get("max_attempts"), 3)));
        return Map.of("schedule", name, "queued", result.created(), "task", result.task());
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
