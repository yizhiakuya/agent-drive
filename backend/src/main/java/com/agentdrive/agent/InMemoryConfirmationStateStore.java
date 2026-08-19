package com.agentdrive.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 仅用于测试和本地运行的并发安全确认状态存储。
 *
 * <p>待确认记录按 sessionId 保存，已消费 nonce 单独保留在集合中，因此清理待确认
 * 记录不会允许同一 nonce 再次通过验证；进程退出后所有状态都会丢失。</p>
 */
public final class InMemoryConfirmationStateStore implements ConfirmationStateStore {
    private final Map<String, Map<String, Object>> pendingBySession = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> consumedBySession = new ConcurrentHashMap<>();

    /**
     * 查找并复制与工具及参数完全相等的待确认项。
     * @param sessionId 会话分区标识；null 按空分区处理
     * @param tool 工具名
     * @param arguments 工具参数
     * @return 待确认项的浅复制；不存在或参数不匹配时返回 null
     */
    @Override
    public Map<String, Object> findPending(String sessionId, String tool, Map<String, Object> arguments) {
        Map<String, Object> pending = pendingBySession.get(key(sessionId));
        if (pending == null || !Objects.equals(tool, pending.get("tool"))) {
            return null;
        }
        Object pendingArguments = pending.get("arguments");
        if (!(pendingArguments instanceof Map<?, ?>)) {
            return null;
        }
        if (!Objects.equals(arguments, pendingArguments)) {
            return null;
        }
        return new LinkedHashMap<>(pending);
    }

    /**
     * 保存待确认项的浅复制，避免调用方之后修改存储中的顶层 Map。
     * @param sessionId 会话分区标识
     * @param pending 待确认项；null 会被忽略
     */
    @Override
    public void savePending(String sessionId, Map<String, Object> pending) {
        if (pending == null) {
            return;
        }
        pendingBySession.put(key(sessionId), new LinkedHashMap<>(pending));
    }

    /**
     * 删除指定会话当前的待确认项。
     * @param sessionId 会话分区标识
     */
    @Override
    public void clearPending(String sessionId) {
        pendingBySession.remove(key(sessionId));
    }

    /**
     * 使用并发集合原子登记一个 nonce，重复登记返回 false。
     * @param sessionId 会话分区标识
     * @param nonce 要消费的 nonce；空值无效
     * @return 首次登记成功时为 true
     */
    @Override
    public boolean consumeNonce(String sessionId, String nonce) {
        if (nonce == null || nonce.isBlank()) {
            return false;
        }
        return consumedBySession.computeIfAbsent(key(sessionId), ignored -> ConcurrentHashMap.newKeySet())
                .add(nonce);
    }

    /**
     * 将 null 会话 ID 映射到内存 Map 可用的空字符串键。
     * @param sessionId 原始会话标识
     * @return 非 null 会话标识，或 null 对应的空字符串
     */
    private String key(String sessionId) {
        return sessionId == null ? "" : sessionId;
    }
}
