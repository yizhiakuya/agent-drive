package com.agentdrive.tasks;

import com.agentdrive.index.IndexExecutionMonitor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 将索引业务的长操作登记到任务模块；不改变索引业务 API 的调用边界。 */
@Service
@Profile({"java-files", "java-auth", "java-chat"})
public final class TaskIndexExecutionMonitor implements IndexExecutionMonitor {
    private final TaskStore tasks;

    public TaskIndexExecutionMonitor(TaskStore tasks) {
        this.tasks = tasks;
    }

    @Override
    public Optional<Submission> submit(UUID userId, String operation, String taskType,
                                       List<String> paths, boolean force, String prefix) {
        List<String> normalizedPaths = paths == null ? List.of() : List.copyOf(paths);
        Map<String, Object> payload = new LinkedHashMap<>();
        if ("index.rebuild".equals(taskType)) {
            payload.put("prefix", prefix == null ? "" : prefix);
            payload.put("force", false);
        } else {
            payload.put("files", normalizedPaths);
            payload.put("force", force);
        }
        String dedupe = "index.rebuild".equals(taskType)
                ? "index.rebuild:" + (prefix == null ? "" : prefix) + ":false"
                : ("index.vision".equals(taskType) ? "index.vision:" : "")
                + IndexTaskPaths.dedupeKey(normalizedPaths, force);
        TaskStore.EnqueueResult result = tasks.enqueue(userId, taskType, "index", payload,
                dedupe, "index-api", null);
        Map<String, Object> task = result.task() == null ? Map.of() : result.task();
        String taskId = String.valueOf(task.getOrDefault("id", ""));
        String status = String.valueOf(task.getOrDefault("status", result.created() ? "queued" : "unknown"));
        return Optional.of(new Submission(result.created(), status, taskId, task));
    }
}
