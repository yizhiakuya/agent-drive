package com.agentdrive.agent;

import java.util.Locale;

/**
 * Agent 工具结果的重放策略。
 *
 * <p>默认不缓存读取结果。读取接口通常反映会变化的文件/配置状态，不能仅凭 HTTP
 * 方法是 GET 就当作永远可重放；只有显式标记的无副作用探测才允许 session 内复用。</p>
 */
public enum ReplayPolicy {
    /** 不保存也不读取历史结果。 */
    NONE,
    /** 可重复执行且结果可安全复用的内部操作。 */
    IDEMPOTENT,
    /** 外部 provider 探测，避免同一轮重试重复消耗网络/配额。 */
    PROBE;

    /**
     * 为尚未显式声明策略的兼容 operation 生成保守默认值。
     * @param method HTTP 方法或 INTERNAL
     * @param path HTTP 路径
     * @return 默认策略
     */
    public static ReplayPolicy defaultFor(String method, String path) {
        String normalizedMethod = method == null ? "" : method.toUpperCase(Locale.ROOT);
        String normalizedPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if ("POST".equals(normalizedMethod)
                && (normalizedPath.endsWith("/models")
                || (normalizedPath.startsWith("/api/v1/config/") && normalizedPath.endsWith("/test")))) {
            return PROBE;
        }
        return NONE;
    }

    /** @return 该策略是否允许读取历史重放结果。 */
    public boolean replayable() {
        return this != NONE;
    }
}
