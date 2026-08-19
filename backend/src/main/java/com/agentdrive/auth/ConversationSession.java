package com.agentdrive.auth;

import java.util.UUID;

/**
 * 会话 ID 与 owner ID 的最小关联记录。
 *
 * <p>服务层和存储层用它确认会话归属；两个 UUID 都不能为空，避免空身份参与
 * owner-scoped 查询。</p>
 */
public record ConversationSession(UUID id, UUID userId) {
    /**
     * 创建并校验会话归属记录。
     * @param id 会话 UUID
     * @param userId 所属 owner UUID
     * @throws IllegalArgumentException 任一 UUID 为空时抛出
     */
    public ConversationSession {
        if (id == null || userId == null) {
            throw new IllegalArgumentException("conversation session ids must not be null");
        }
    }
}
