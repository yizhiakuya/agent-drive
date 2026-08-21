package com.agentdrive.tasks;

import com.agentdrive.progress.TaskProgressReporter;

import java.util.Map;
import java.util.UUID;

/** 执行一个持久化聊天任务；异常由 Worker 状态机转换为失败。 */
@FunctionalInterface
public interface ChatTaskExecutor {
    /**
     * 执行 owner-scoped chat.run payload。
     * @param userId 任务归属 owner
     * @param payload 受控任务参数
     * @param progress 任务进度与租约回调
     * @return 可写入 tasks.result 的摘要
     */
    Map<String, Object> execute(UUID userId, Map<String, Object> payload, TaskProgressReporter progress);
}
