package com.agentdrive.infrastructure;

import com.agentdrive.auth.ConversationSession;
import com.agentdrive.auth.ConversationSessionStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 将聊天会话元数据和消息切换到独立 Agent Service。 */
public final class RemoteConversationSessionStore implements ConversationSessionStore {
    private final RemoteAgentStateClient client;

    public RemoteConversationSessionStore(RemoteAgentStateClient client) {
        this.client = client;
    }

    @Override
    public Optional<ConversationSession> findOwned(UUID userId, UUID sessionId) {
        Map<String, Object> result = client.call("session.find", Map.of(
                "owner_id", userId.toString(), "session_id", sessionId.toString()));
        if (!Boolean.TRUE.equals(result.get("found"))) return Optional.empty();
        Map<?, ?> value = (Map<?, ?>) result.get("session");
        return Optional.of(new ConversationSession(UUID.fromString(String.valueOf(value.get("id"))),
                UUID.fromString(String.valueOf(value.get("user_id")))));
    }

    @Override
    public ConversationSession create(UUID userId) {
        Map<String, Object> result = client.call("session.create", Map.of("owner_id", userId.toString()));
        return new ConversationSession(UUID.fromString(String.valueOf(result.get("id"))), userId);
    }

    @Override
    public List<Map<String, Object>> listOwned(UUID userId) {
        return list(client.call("session.list", Map.of("owner_id", userId.toString())));
    }

    @Override
    public Map<String, Object> findOwnedDetails(UUID userId, UUID sessionId) {
        Map<String, Object> result = client.call("session.details", Map.of(
                "owner_id", userId.toString(), "session_id", sessionId.toString()));
        return Boolean.TRUE.equals(result.get("found")) ? castMap(result.get("session")) : null;
    }

    @Override
    public List<Map<String, Object>> messagesOwned(UUID userId, UUID sessionId) {
        return list(client.call("session.messages", Map.of(
                "owner_id", userId.toString(), "session_id", sessionId.toString())));
    }

    @Override
    public boolean deleteOwned(UUID userId, UUID sessionId) {
        return Boolean.TRUE.equals(client.call("session.delete", Map.of(
                "owner_id", userId.toString(), "session_id", sessionId.toString())).get("deleted"));
    }

    @Override
    public boolean updateSummary(UUID userId, UUID sessionId, String summary, String title) {
        return Boolean.TRUE.equals(client.call("session.update_summary", Map.of(
                "owner_id", userId.toString(), "session_id", sessionId.toString(),
                "summary", summary == null ? "" : summary, "title", title == null ? "" : title)).get("updated"));
    }

    private List<Map<String, Object>> list(Map<String, Object> result) {
        Object value = result.get("items");
        if (!(value instanceof List<?> items)) return List.of();
        return items.stream().filter(Map.class::isInstance).map(RemoteConversationSessionStore::castMap).toList();
    }

    private static Map<String, Object> castMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
