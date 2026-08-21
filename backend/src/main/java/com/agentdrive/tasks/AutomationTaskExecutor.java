package com.agentdrive.tasks;

import com.agentdrive.progress.TaskProgressReporter;

import java.util.Map;
import java.util.UUID;

/**
 * 执行持久化任务中的自动化动作。
 * 具体实现负责解释任务 payload，并返回可写入任务结果的结构化数据。
 */
public interface AutomationTaskExecutor {
    default Map<String, Object> execute(UUID userId, Map<String, Object> payload, TaskProgressReporter progress) {
        return execute(userId, payload);
    }

    /**
     * 按用户身份执行一条 automation 任务；旧实现保留该窄接口。
     * @param userId 任务所属用户的 UUID。
     * @param payload 自动化任务参数。
     * @return 自动化动作产生的结构化结果。
     */
    Map<String, Object> execute(UUID userId, Map<String, Object> payload);
}
