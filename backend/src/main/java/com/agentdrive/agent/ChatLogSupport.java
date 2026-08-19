package com.agentdrive.agent;

import java.util.regex.Pattern;

/**
 * 为 Agent 工具日志提供不依赖 API 层的异常脱敏。
 *
 * <p>该类放在 Agent 核心包中，避免工具适配器反向依赖聊天 Controller。它只保留安全
 * 的异常消息和原始堆栈位置，不保留原始 cause/suppressed 文本；查询参数凭据与常见
 * Provider/Bearer 凭据都在进入日志前替换。</p>
 */
public final class ChatLogSupport {
    private static final Pattern OPENAI_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern JINA_KEY = Pattern.compile("\\bjina_[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern QUERY_CREDENTIAL = Pattern.compile(
            "(?i)((?:^|[?&\\s])(?:api[_-]?key|token|password|secret|authorization|cookie)=)[^&\\s]+"
    );

    /**
     * 禁止实例化静态日志支持类。
     */
    private ChatLogSupport() {
    }

    /**
     * 返回不含已知凭据的异常消息。
     *
     * @param error 原始异常
     * @return 脱敏后的异常消息；没有可用消息时返回稳定错误文案
     */
    public static String message(Throwable error) {
        if (error == null) {
            return "chat stream failed";
        }
        String raw = error.getMessage();
        String value = raw == null || raw.isBlank() ? "chat stream failed" : raw;
        String redacted = BEARER.matcher(JINA_KEY.matcher(OPENAI_KEY.matcher(value)
                .replaceAll("[REDACTED]"))
                .replaceAll("[REDACTED]"))
                .replaceAll("Bearer [REDACTED]");
        return QUERY_CREDENTIAL.matcher(redacted).replaceAll("$1[REDACTED]");
    }

    /**
     * 创建只保留原始堆栈位置的安全异常。
     *
     * @param error 原始异常
     * @return message、cause 和 suppressed 均已隔离的异常对象
     */
    public static Throwable safeThrowable(Throwable error) {
        Throwable source = error == null ? new IllegalStateException("chat stream failed") : error;
        RuntimeException safe = new SanitizedException(message(source));
        safe.setStackTrace(source.getStackTrace());
        return safe;
    }

    /**
     * 不捕获当前调用点堆栈的安全异常容器。
     */
    private static final class SanitizedException extends RuntimeException {
        private SanitizedException(String message) {
            super(message);
        }
    }
}
