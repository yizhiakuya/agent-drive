package com.agentdrive.agent;

import java.util.Map;

/**
 * 保存高风险工具确认的待处理记录及 nonce 消费状态。
 *
 * <p>实现必须保证同一会话和 nonce 只能成功消费一次；生产实现可将数据放入
 * PostgreSQL，测试则可使用内存实现。</p>
 */
public interface ConfirmationStateStore {
    /**
     * 查找指定会话中与工具名和参数完全匹配的待确认记录。
     * @param sessionId 会话分区标识
     * @param tool 工具名
     * @param arguments 原始工具参数
     * @return 待确认记录；无匹配项时返回 null
     */
    Map<String, Object> findPending(String sessionId, String tool, Map<String, Object> arguments);

    /**
     * 保存或覆盖一个会话当前的待确认记录。
     * @param sessionId 会话分区标识
     * @param pending 包含工具、原始参数、nonce、时间戳和签名的记录
     */
    void savePending(String sessionId, Map<String, Object> pending);

    /**
     * 删除会话当前待确认记录；nonce 消费历史不应因此被删除。
     * @param sessionId 会话分区标识
     */
    void clearPending(String sessionId);

    /**
     * 原子记录 nonce 已被消费，防止确认请求重放。
     * @param sessionId 会话分区标识
     * @param nonce 待消费的确认 nonce
     * @return 本次首次消费成功时为 true，已消费或无效时为 false
     */
    boolean consumeNonce(String sessionId, String nonce);
}
