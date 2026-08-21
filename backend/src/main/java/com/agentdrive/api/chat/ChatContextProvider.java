package com.agentdrive.api.chat;

import java.util.List;
import java.util.UUID;

/** 为一次 owner-scoped 模型请求装配当前上下文。 */
@FunctionalInterface
public interface ChatContextProvider {
    /**
     * 读取当前 owner 的完整上下文快照。
     * @param userId 已认证 owner
     * @return 按模型读取顺序排列的上下文
     */
    List<ChatContext> contexts(UUID userId);

    /**
     * 返回不提供额外上下文的实现。
     * @return 空上下文 provider
     */
    static ChatContextProvider none() {
        return ignored -> List.of();
    }
}
