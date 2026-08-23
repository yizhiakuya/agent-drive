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
     * 读取基础上下文并追加本轮由用户选择的文件/目录上下文。旧 provider 可以只实现
     * {@link #contexts(UUID)}，这样不会改变兼容测试和无文件请求的行为。
     *
     * @param userId 已认证 owner
     * @param filePaths owner 根下的相对路径
     * @return 基础上下文和本轮文件上下文
     */
    default List<ChatContext> contexts(UUID userId, List<String> filePaths) {
        return contexts(userId);
    }

    /**
     * 读取带会话状态的上下文。默认委托到旧签名，兼容不需要 Skill 历史的实现。
     *
     * @param userId 已认证 owner
     * @param sessionId 当前会话 UUID 文本，可为空
     * @param filePaths owner 根下的相对路径
     * @return 基础上下文、会话已加载 Skill 和本轮文件上下文
     */
    default List<ChatContext> contexts(UUID userId, String sessionId, List<String> filePaths) {
        return contexts(userId, filePaths);
    }

    /**
     * 按当前用户消息编译上下文；旧 provider 默认保留完整上下文行为。
     *
     * @param userId 已认证 owner
     * @param sessionId 当前会话 ID
     * @param filePaths 本轮引用的 owner 相对路径
     * @param userMessage 当前用户消息，供上下文策略判断相关性
     * @return 当前请求需要的上下文快照
     */
    default List<ChatContext> contexts(UUID userId, String sessionId,
                                       List<String> filePaths, String userMessage) {
        return contexts(userId, sessionId, filePaths);
    }

    /**
     * 返回不提供额外上下文的实现。
     * @return 空上下文 provider
     */
    static ChatContextProvider none() {
        return ignored -> List.of();
    }
}
