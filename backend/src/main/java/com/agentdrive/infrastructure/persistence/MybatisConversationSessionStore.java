package com.agentdrive.infrastructure.persistence;

import com.agentdrive.auth.ConversationSession;
import com.agentdrive.auth.ConversationSessionStore;
import com.agentdrive.infrastructure.persistence.mapper.ConversationSessionMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 通过 MyBatis 读写 owner-scoped 聊天会话、消息、摘要和标题。
 * <p>查询始终把 owner UUID 传入 SQL；消息中的 arguments/parsed JSON 在返回前解析，
 * 解析失败时保留原始文本，避免丢失历史记录。</p>
 */
public final class MybatisConversationSessionStore implements ConversationSessionStore {
    private final ConversationSessionMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * 使用默认 Jackson 映射器创建会话存储，兼容直接构造的测试或迁移场景。
     * @param mapper 访问会话和消息表的 Mapper。
     */
    public MybatisConversationSessionStore(ConversationSessionMapper mapper) {
        this(mapper, new ObjectMapper());
    }

    /**
     * 保存会话 Mapper 和消息 JSON 映射器。
     * @param mapper 执行 owner 过滤的会话/消息 SQL Mapper。
     * @param objectMapper 解析消息扩展 JSON 字段的 Jackson 映射器。
     */
    public MybatisConversationSessionStore(ConversationSessionMapper mapper, ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 按 owner 和 session ID 查找会话归属。
     * @param userId 请求方 owner 的 UUID。
     * @param sessionId 要查找的会话 UUID。
     * @return 会话存在且属于该 owner 时返回会话对象，否则为空。
     */
    @Override
    public Optional<ConversationSession> findOwned(UUID userId, UUID sessionId) {
        if (userId == null || sessionId == null) {
            return Optional.empty();
        }
        Map<String, Object> row = mapper.selectOwned(userId.toString(), sessionId.toString());
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new ConversationSession(
                UUID.fromString(String.valueOf(row.get("id"))),
                UUID.fromString(String.valueOf(row.get("user_id")))
        ));
    }

    /**
     * 列出 owner 的会话元数据。
     * @param userId 请求方 owner 的 UUID。
     * @return 数据库返回的会话行规范化后的列表。
     * @throws NullPointerException userId 为空时抛出。
     */
    @Override
    public List<Map<String, Object>> listOwned(UUID userId) {
        requireUser(userId);
        return mapper.selectOwnedList(userId.toString()).stream().map(this::normalizeMeta).toList();
    }

    /**
     * 读取某个 owner 会话的完整元数据。
     * @param userId 请求方 owner 的 UUID。
     * @param sessionId 会话 UUID；为空时不访问数据库并返回 {@code null}。
     * @return 规范化的会话 Map；记录不存在时为 {@code null}。
     */
    @Override
    public Map<String, Object> findOwnedDetails(UUID userId, UUID sessionId) {
        requireUser(userId);
        if (sessionId == null) {
            return null;
        }
        Map<String, Object> row = mapper.selectOwnedDetails(userId.toString(), sessionId.toString());
        return row == null ? null : normalizeMeta(row);
    }

    /**
     * 读取 owner 会话的消息，并解析工具调用扩展字段。
     * @param userId 请求方 owner 的 UUID。
     * @param sessionId 会话 UUID；为空时返回空列表。
     * @return 按数据库顺序返回 role、content、reasoning、tool 和解析后的参数/结果。
     */
    @Override
    public List<Map<String, Object>> messagesOwned(UUID userId, UUID sessionId) {
        requireUser(userId);
        if (sessionId == null) {
            return List.of();
        }
        return mapper.selectMessages(userId.toString(), sessionId.toString()).stream()
                .map(this::normalizeMessage)
                .toList();
    }

    /**
     * 删除属于 owner 的会话及其关联消息。
     * @param userId 请求方 owner 的 UUID。
     * @param sessionId 要删除的会话 UUID。
     * @return 数据库实际删除一行或以上时为 {@code true}。
     */
    @Override
    public boolean deleteOwned(UUID userId, UUID sessionId) {
        requireUser(userId);
        return sessionId != null && mapper.deleteOwned(userId.toString(), sessionId.toString()) > 0;
    }

    /**
     * 更新 owner 会话的摘要和标题。
     * @param userId 请求方 owner 的 UUID。
     * @param sessionId 会话 UUID。
     * @param summary 新摘要文本。
     * @param title 新标题；可为空以保留后端对空标题的处理语义。
     * @return 数据库更新一行时为 {@code true}。
     */
    @Override
    public boolean updateSummary(UUID userId, UUID sessionId, String summary, String title) {
        requireUser(userId);
        return sessionId != null && mapper.updateSummary(userId.toString(), sessionId.toString(), summary, title) > 0;
    }

    /**
     * 为 owner 创建一个新的聊天会话。
     * @param userId 新会话所属 owner 的 UUID。
     * @return 数据库生成 ID 后构造的会话对象。
     * @throws IllegalArgumentException userId 为空或数据库返回的 ID 不是 UUID 时抛出。
     * @throws IllegalStateException 数据库没有返回会话 ID 时抛出。
     */
    @Override
    public ConversationSession create(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        String id = mapper.insertSession(userId.toString());
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("database did not return a chat session id");
        }
        return new ConversationSession(UUID.fromString(id), userId);
    }

    /**
     * 复制会话元数据行，保持 Mapper 字段和值不变。
     * @param row 数据库返回的会话行。
     * @return 可供上层修改的有序 Map 副本。
     */
    private Map<String, Object> normalizeMeta(Map<String, Object> row) {
        return new LinkedHashMap<>(row);
    }

    /**
     * 将消息数据库行转换为聊天 API 使用的字段结构。
     * @param row 包含 role/content/工具扩展列的数据库行。
     * @return 只保留消息契约字段，并将 JSON 扩展解析为对象的有序 Map。
     */
    private Map<String, Object> normalizeMessage(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", row.get("role"));
        result.put("content", row.get("content"));
        if (row.get("reasoning") != null) result.put("reasoning", row.get("reasoning"));
        if (row.get("tool_name") != null) result.put("tool", row.get("tool_name"));
        if (row.get("arguments_json") != null) result.put("arguments", parseJson(row.get("arguments_json")));
        if (row.get("parsed_json") != null) result.put("parsed", parseJson(row.get("parsed_json")));
        result.put("ts", row.get("ts"));
        return result;
    }

    /**
     * 解析消息扩展 JSON；无法解析时保留数据库原值。
     * @param value JSON 文本或数据库返回的其他值。
     * @return 解析后的对象，或解析失败时的原始值。
     */
    private Object parseJson(Object value) {
        try {
            return objectMapper.readValue(String.valueOf(value), Object.class);
        } catch (JsonProcessingException error) {
            return value;
        }
    }

    /**
     * 在调用 owner-scoped SQL 前校验 owner UUID。
     * @param userId 请求方 owner 的 UUID。
     * @throws NullPointerException userId 为空时抛出。
     */
    private static void requireUser(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
    }
}
