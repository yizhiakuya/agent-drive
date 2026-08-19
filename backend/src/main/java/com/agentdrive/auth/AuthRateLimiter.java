package com.agentdrive.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于固定一分钟窗口的进程内认证限流器。
 *
 * <p>每个 key 保存最近命中时间；超过 limit 时拒绝请求。key 数超过 1000 后会清理
 * 已过期桶，避免异常客户端标识无限增长。它只适合单 API 进程部署，重启后计数丢失。</p>
 */
public final class AuthRateLimiter {
    public static final int DEFAULT_LIMIT = 5;
    public static final int PAIRING_EXCHANGE_LIMIT = 10;
    public static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int MAX_KEYS = 1000;

    private final ConcurrentHashMap<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    /**
     * 尝试在一分钟窗口内为 key 记录一次请求。
     *
     * <p>方法在单 key 桶上同步清理过期时间并检查上限；当全局 key 数超过阈值时，
     * 额外删除空桶和整个窗口内没有命中的桶。</p>
     * @param key 客户端或业务动作的限流键
     * @param limit 该 key 在窗口内允许的最大次数
     * @return 参数有效且本次请求未超过上限时为 true
     */
    public boolean allow(String key, int limit) {
        if (key == null || key.isBlank() || limit < 1) {
            return false;
        }
        Instant now = Instant.now();
        Deque<Instant> bucket = hits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (bucket) {
            Instant cutoff = now.minus(WINDOW);
            while (!bucket.isEmpty() && bucket.peekFirst().isBefore(cutoff)) {
                bucket.removeFirst();
            }
            if (bucket.size() >= limit) {
                return false;
            }
            bucket.addLast(now);
        }
        if (hits.size() > MAX_KEYS) {
            hits.entrySet().removeIf(entry -> {
                Deque<Instant> candidate = entry.getValue();
                synchronized (candidate) {
                    return candidate.isEmpty() || candidate.peekLast().isBefore(now.minus(WINDOW));
                }
            });
        }
        return true;
    }
}
