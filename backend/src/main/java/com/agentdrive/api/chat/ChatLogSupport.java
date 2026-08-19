package com.agentdrive.api.chat;

/**
 * 聊天层的日志脱敏兼容门面。
 *
 * <p>实际实现位于 Agent 核心包，聊天 API 只保留这个向后兼容的同包入口。这样既不
 * 破坏现有测试和调用点，也避免 Agent 工具反向依赖 {@code api.chat}。</p>
 */
public final class ChatLogSupport {
    /**
     * 禁止实例化兼容门面。
     */
    private ChatLogSupport() {
    }

    /**
     * 返回不含已知凭据的异常消息，供 SSE 或普通日志字段使用。
     *
     * @param error 原始异常
     * @return 脱敏后的异常消息；没有可用消息时返回稳定错误文案
     */
    public static String message(Throwable error) {
        return com.agentdrive.agent.ChatLogSupport.message(error);
    }

    /**
     * 创建只保留原始堆栈位置的安全异常，供 logger.error 的 throwable 参数使用。
     *
     * @param error 原始异常
     * @return message/cause/suppressed 均已隔离的异常对象；空值返回固定异常
     */
    public static Throwable safeThrowable(Throwable error) {
        return com.agentdrive.agent.ChatLogSupport.safeThrowable(error);
    }
}
