package com.agentdrive.auth;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 提供会话的 owner 隔离、读取、删除和摘要生成服务。
 *
 * <p>所有公开方法先确认 userId，再把请求的 session_id 解析为 UUID 并通过存储层
 * 做归属检查。摘要只取 user/assistant 正文，过滤工具标记、折叠空白并限制为 80
 * 个 Unicode code point；空摘要不会覆盖已有摘要或标题。标题仍是占位符时，会先
 * 使用摘要前 20 个 code point，再尽力调用可选的 AI 生成器。</p>
 */
public final class ConversationSessionService {
    private static final Set<String> PLACEHOLDER_TITLES = Set.of(
            "A session", "New session", "Untitled session", "无标题会话", "（无标题会话）", "(无标题会话)"
    );

    private final ConversationSessionStore store;
    private final SessionTitleGenerator titleGenerator;

    /**
     * 创建不启用 AI 标题生成的会话服务。
     * @param store 会话元数据和消息的 owner-scoped 存储
     */
    public ConversationSessionService(ConversationSessionStore store) {
        this(store, null);
    }

    /**
     * 创建带可选标题生成器的会话服务。
     * @param store 会话元数据和消息的 owner-scoped 存储
     * @param titleGenerator 从会话消息生成标题的服务；为空时使用确定性摘要标题
     */
    public ConversationSessionService(ConversationSessionStore store, SessionTitleGenerator titleGenerator) {
        this.store = store;
        this.titleGenerator = titleGenerator;
    }

    /**
     * 返回可供聊天请求继续使用的 owner 会话 ID。
     *
     * <p>session_id 为空时创建新会话；非空时必须是合法 UUID 且属于当前用户，
     * 否则分别抛出参数或未找到异常。</p>
     * @param userId 当前认证用户
     * @param requestedSessionId 客户端传入的会话 ID，可为空
     * @return 已存在或新建会话的 UUID 字符串
     * @throws UnauthorizedException userId 为空时抛出
     * @throws InvalidSessionIdException session ID 不是 UUID 时抛出
     * @throws SessionNotFoundException 会话不存在或不属于当前用户时抛出
     */
    public String ensureOwned(UUID userId, String requestedSessionId) {
        if (userId == null) {
            throw new UnauthorizedException("authenticated user is required");
        }
        if (requestedSessionId == null || requestedSessionId.isBlank()) {
            return store.create(userId).id().toString();
        }
        UUID sessionId = parseSessionId(requestedSessionId);
        ConversationSession session = store.findOwned(userId, sessionId)
                .orElseThrow(() -> new SessionNotFoundException("chat session is not owned by current user"));
        if (!userId.equals(session.userId())) {
            throw new SessionNotFoundException("chat session is not owned by current user");
        }
        return session.id().toString();
    }

    /**
     * 列出当前用户拥有的会话摘要。
     * @param userId 当前认证用户
     * @return 存储层返回的 owner-scoped 会话列表
     * @throws UnauthorizedException userId 为空时抛出
     */
    public List<Map<String, Object>> listOwned(UUID userId) {
        requireUser(userId);
        return store.listOwned(userId);
    }

    /**
     * 读取当前用户会话的元数据和消息。
     * @param userId 当前认证用户
     * @param requestedSessionId 要读取的 UUID 字符串
     * @return 会话元数据与消息快照
     * @throws UnauthorizedException userId 为空时抛出
     * @throws InvalidSessionIdException session ID 不是 UUID 时抛出
     * @throws SessionNotFoundException 会话不存在或不属于当前用户时抛出
     */
    public SessionDetails getOwned(UUID userId, String requestedSessionId) {
        requireUser(userId);
        UUID sessionId = parseSessionId(requestedSessionId);
        Map<String, Object> meta = store.findOwnedDetails(userId, sessionId);
        if (meta == null) {
            throw new SessionNotFoundException("chat session does not exist");
        }
        return new SessionDetails(meta, store.messagesOwned(userId, sessionId));
    }

    /**
     * 删除当前用户拥有的会话及其关联记录。
     * @param userId 当前认证用户
     * @param requestedSessionId 要删除的 UUID 字符串
     * @throws UnauthorizedException userId 为空时抛出
     * @throws InvalidSessionIdException session ID 不是 UUID 时抛出
     * @throws SessionNotFoundException 删除目标不存在或不属于当前用户时抛出
     */
    public void deleteOwned(UUID userId, String requestedSessionId) {
        requireUser(userId);
        UUID sessionId = parseSessionId(requestedSessionId);
        if (!store.deleteOwned(userId, sessionId)) {
            throw new SessionNotFoundException("chat session does not exist");
        }
    }

    /**
     * 从 user/assistant 消息生成并持久化会话摘要和标题。
     *
     * <p>工具消息和空正文会被忽略，正文中的标签样式片段会被删除；摘要为空时
     * 只返回已有值而不写库。标题已存在时保持原值，只有占位标题才调用生成器，且
     * 生成失败回退到确定性标题。</p>
     * @param userId 当前认证用户
     * @param requestedSessionId 要总结的 UUID 字符串
     * @return {@code ok}、summary 和 title 字段
     * @throws UnauthorizedException userId 为空时抛出
     * @throws InvalidSessionIdException session ID 不是 UUID 时抛出
     * @throws SessionNotFoundException 会话不存在或更新时已消失时抛出
     */
    public Map<String, Object> summarizeOwned(UUID userId, String requestedSessionId) {
        requireUser(userId);
        UUID sessionId = parseSessionId(requestedSessionId);
        SessionDetails details = getOwned(userId, requestedSessionId);
        String summary = details.messages().stream()
                .filter(message -> "user".equals(message.get("role")) || "assistant".equals(message.get("role")))
                .map(ConversationSessionService::messageContent)
                .filter(content -> !content.isBlank())
                .map(ConversationSessionService::collapseWhitespace)
                .reduce((left, right) -> left + "；" + right)
                .orElse("")
                .replaceAll("<[^>]{1,80}>", "")
                .trim();
        if (summary.length() > 80) summary = limitCodePoints(summary, 80);
        // 空摘要（如会话尚无正文消息、或消息全部为 null）不落库：不覆盖已有标题，
        // 也不把空标题固化；返回当前已存值，由前端在后续会话变更时重试总结。
        if (summary.isBlank()) {
            return Map.of("ok", true,
                    "summary", stringValue(details.meta().get("summary")),
                    "title", stringValue(details.meta().get("title")));
        }
        String storedTitle = stringValue(details.meta().get("title"));
        boolean needsGeneratedTitle = isPlaceholderTitle(storedTitle);
        String title = needsGeneratedTitle ? limitCodePoints(summary, 20) : storedTitle;
        if (needsGeneratedTitle && titleGenerator != null) {
            try {
                title = normalizeGeneratedTitle(titleGenerator.generate(userId, details.messages()), title);
            } catch (RuntimeException ignored) {
                // Title generation is best effort; the deterministic title remains usable.
            }
        }
        if (!store.updateSummary(userId, sessionId, summary, title)) {
            throw new SessionNotFoundException("chat session does not exist");
        }
        return Map.of("ok", true, "summary", summary, "title", title);
    }

    /**
     * 判断标题是否为空或属于已知占位标题。
     * @param title 会话当前标题
     * @return 需要用摘要/生成器替换时为 true
     */
    private static boolean isPlaceholderTitle(String title) {
        return title.isBlank() || PLACEHOLDER_TITLES.contains(title.trim());
    }

    /**
     * 清理 AI 返回的标题前缀、空白并限制长度，空结果回退到确定性标题。
     * @param generated 标题生成器返回的文本
     * @param fallback 生成失败或空结果时使用的标题
     * @return 最多 20 个 Unicode code point 的标题
     */
    private static String normalizeGeneratedTitle(String generated, String fallback) {
        if (generated == null || generated.isBlank()) {
            return fallback;
        }
        String title = collapseWhitespace(generated)
                .replaceFirst("^(?i:标题|title)\\s*[:：]\\s*", "")
                .trim();
        return title.isBlank() ? fallback : limitCodePoints(title, 20);
    }

    /** 读取消息正文，并将工具型 assistant 的 null 正文转换为空字符串。 */
    private static String messageContent(Map<String, Object> message) {
        Object content = message.get("content");
        return content == null ? "" : String.valueOf(content);
    }

    /**
     * 把连续空白折叠为单个空格并去除首尾空白。
     * @param content 消息或标题文本
     * @return 规范化后的文本
     */
    private static String collapseWhitespace(String content) {
        return content.replaceAll("\\s+", " ").trim();
    }

    /**
     * 按 Unicode code point 截断字符串，避免截断代理项。
     * @param value 待截断文本；null 原样返回
     * @param max 最大 code point 数
     * @return 不超过 max 的文本
     */
    private static String limitCodePoints(String value, int max) {
        if (value == null || value.codePointCount(0, value.length()) <= max) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max));
    }

    /**
     * 把存储元数据值转换为文本，null 映射为空字符串。
     * @param value 存储层返回的任意值
     * @return 非 null 的字符串表示或空字符串
     */
    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 确认公开会话操作带有认证用户。
     * @param userId 当前用户 UUID
     * @throws UnauthorizedException userId 为空时抛出
     */
    private void requireUser(UUID userId) {
        if (userId == null) {
            throw new UnauthorizedException("authenticated user is required");
        }
    }

    /**
     * 将客户端 session_id 转为 UUID，并把格式错误转换为业务异常。
     * @param value 客户端传入的会话 ID
     * @return 解析后的 UUID
     * @throws InvalidSessionIdException value 不是合法 UUID 时抛出
     */
    private UUID parseSessionId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw new InvalidSessionIdException("session_id must be a UUID", error);
        }
    }

    /** 一次会话读取返回的元数据和按时间顺序排列的消息。 */
    public record SessionDetails(Map<String, Object> meta, List<Map<String, Object>> messages) {
    }

    /** 表示会话操作缺少认证用户。 */
    public static class UnauthorizedException extends RuntimeException {
        /** @param message 面向 API 层的错误说明 */
        public UnauthorizedException(String message) {
            super(message);
        }
    }

    /** 表示客户端 session_id 不是合法 UUID。 */
    public static class InvalidSessionIdException extends RuntimeException {
        /**
         * @param message 面向 API 层的错误说明
         * @param cause UUID 解析失败的原始异常
         */
        public InvalidSessionIdException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 表示会话不存在、已删除或不属于当前用户。 */
    public static class SessionNotFoundException extends RuntimeException {
        /** @param message 面向 API 层的错误说明 */
        public SessionNotFoundException(String message) {
            super(message);
        }
    }
}
