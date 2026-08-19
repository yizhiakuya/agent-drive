package com.agentdrive.auth;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话元数据、消息和摘要的 owner-scoped 持久化接口。
 *
 * <p>所有实现都必须把 userId 作为查询条件，不能只按 sessionId 读写；default 方法
 * 提供给只实现基础会话创建/查找的轻量测试替身，生产存储应覆盖需要的查询和写操作。</p>
 */
public interface ConversationSessionStore {
    /**
     * 查找属于指定用户的会话关联。
     * @param userId 查询边界的 owner UUID
     * @param sessionId 会话 UUID
     * @return 属于该用户的会话；不存在或归属不符时为空
     */
    Optional<ConversationSession> findOwned(UUID userId, UUID sessionId);

    /**
     * 为用户创建新的会话记录。
     * @param userId 新会话所属 owner UUID
     * @return 新会话的 ID 和 owner 关联
     */
    ConversationSession create(UUID userId);

    /**
     * 列出用户可见的会话摘要，默认实现返回空列表。
     * @param userId owner UUID
     * @return 会话列表，生产实现应按最近活动或存储约定排序
     */
    default List<Map<String, Object>> listOwned(UUID userId) {
        return List.of();
    }

    /**
     * 读取用户会话的元数据，默认实现返回 null。
     * @param userId owner UUID
     * @param sessionId 会话 UUID
     * @return 元数据 Map；不存在时为 null
     */
    default Map<String, Object> findOwnedDetails(UUID userId, UUID sessionId) {
        return null;
    }

    /**
     * 读取用户会话的消息，默认实现返回空列表。
     * @param userId owner UUID
     * @param sessionId 会话 UUID
     * @return 按会话顺序排列的消息 Map 列表
     */
    default List<Map<String, Object>> messagesOwned(UUID userId, UUID sessionId) {
        return List.of();
    }

    /**
     * 删除用户拥有的会话及其消息，默认实现不执行删除。
     * @param userId owner UUID
     * @param sessionId 会话 UUID
     * @return 实际删除记录时为 true
     */
    default boolean deleteOwned(UUID userId, UUID sessionId) {
        return false;
    }

    /**
     * 更新用户会话的摘要和标题，默认实现不写入。
     * @param userId owner UUID
     * @param sessionId 会话 UUID
     * @param summary 清洗并截断后的会话摘要
     * @param title 保留或生成的会话标题
     * @return 实际更新记录时为 true
     */
    default boolean updateSummary(UUID userId, UUID sessionId, String summary, String title) {
        return false;
    }
}
