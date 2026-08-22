package com.agentdrive.api.chat;

import com.agentdrive.agent.BackendApiRequest;
import com.agentdrive.tasks.TaskStore;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatBackendApiTaskHandlerTest {
    @Test
    void distinguishesMissingAndNonRetryableTasks() {
        UUID owner = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        UUID queuedId = UUID.randomUUID();
        TaskStore tasks = mock(TaskStore.class);
        when(tasks.retry(owner, missingId)).thenReturn(
                new TaskStore.TransitionResult(null, false, "task_not_found"));
        when(tasks.retry(owner, queuedId)).thenReturn(new TaskStore.TransitionResult(
                Map.of("id", queuedId.toString(), "status", "queued"), false, "task_not_retryable"));
        ChatBackendApiTaskHandler handler = new ChatBackendApiTaskHandler(tasks);

        assertThat(handler.dispatch("POST /api/v1/tasks/{task_id}/retry",
                request("POST /api/v1/tasks/{task_id}/retry", missingId), owner))
                .containsEntry("ok", false)
                .containsEntry("error", "task_not_found");
        assertThat(handler.dispatch("POST /api/v1/tasks/{task_id}/retry",
                request("POST /api/v1/tasks/{task_id}/retry", queuedId), owner))
                .containsEntry("ok", false)
                .containsEntry("error", "task_not_retryable");
    }

    @Test
    void returnsIdempotentCancelWithoutInventingAnotherTransition() {
        UUID owner = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskStore tasks = mock(TaskStore.class);
        when(tasks.cancel(owner, taskId)).thenReturn(new TaskStore.TransitionResult(
                Map.of("id", taskId.toString(), "status", "cancelled"), false, "task_not_active"));
        ChatBackendApiTaskHandler handler = new ChatBackendApiTaskHandler(tasks);

        assertThat(handler.dispatch("POST /api/v1/tasks/{task_id}/cancel",
                request("POST /api/v1/tasks/{task_id}/cancel", taskId), owner))
                .containsEntry("changed", false)
                .containsKey("task")
                .doesNotContainKey("error");
    }

    @Test
    void returnsTaskDomainStateWithoutAgentSchedulingInstructions() {
        UUID owner = UUID.randomUUID();
        TaskStore tasks = mock(TaskStore.class);
        Map<String, Object> task = Map.of("id", UUID.randomUUID().toString(), "status", "queued");
        when(tasks.enqueue(org.mockito.ArgumentMatchers.eq(owner),
                org.mockito.ArgumentMatchers.eq("index.clear_vectors"),
                org.mockito.ArgumentMatchers.eq("index"),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.eq("index.clear_vectors"),
                org.mockito.ArgumentMatchers.eq("agent"),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new TaskStore.EnqueueResult(task, true));
        ChatBackendApiTaskHandler handler = new ChatBackendApiTaskHandler(tasks);

        Map<String, Object> result = handler.dispatch(
                "POST /api/v1/tasks/clear-vectors",
                new BackendApiRequest("call", null, "POST /api/v1/tasks/clear-vectors",
                        null, Map.of(), null, null), owner);

        assertThat(result).containsEntry("created", true).containsEntry("task", task)
                .doesNotContainKeys("execution_mode", "message", "queued");
    }

    private BackendApiRequest request(String operation, UUID taskId) {
        return new BackendApiRequest("call", null, operation,
                Map.of("task_id", taskId.toString()), null, null, null);
    }
}
