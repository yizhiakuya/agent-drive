package com.agentdrive.index;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 索引业务对长操作监控的可选端口。
 *
 * <p>索引域只依赖这个抽象，不依赖任务模块；任务模块可以实现它并把业务执行
 * 登记到自己的状态库。没有监控实现时，索引域继续同步执行单项操作。</p>
 */
public interface IndexExecutionMonitor {
    Optional<Submission> submit(UUID userId, String operation, String taskType,
                                 List<String> paths, boolean force, String prefix);

    record Submission(boolean created, String status, String taskId, Map<String, Object> task) {
    }
}
