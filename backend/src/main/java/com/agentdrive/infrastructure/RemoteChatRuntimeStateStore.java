package com.agentdrive.infrastructure;

import com.agentdrive.agent.ChatRunStateStore;
import com.agentdrive.agent.ChatTranscriptStore;
import com.agentdrive.agent.ConfirmationStateStore;
import com.agentdrive.agent.ToolReplayStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Agent/Chat 运行时状态的远程持久化实现。 */
public final class RemoteChatRuntimeStateStore implements PersistentChatRuntimeStateStore {
    private final RemoteAgentStateClient client;
    private final SensitiveDataRedactor redactor = new SensitiveDataRedactor();

    public RemoteChatRuntimeStateStore(RemoteAgentStateClient client) {
        this.client = client;
    }

    @Override
    public List<String> loadedSkillNames(String sessionId) {
        Object value = client.call("runtime.loaded_skills", Map.of("session_id", sessionId)).get("items");
        if (!(value instanceof List<?> items)) return List.of();
        return items.stream().map(String::valueOf).filter(name -> !name.isBlank()).distinct().toList();
    }

    @Override
    public ToolReplay find(String sessionId, String tool, Map<String, Object> arguments) {
        Map<String, Object> result = client.call("runtime.find_replay", Map.of(
                "session_id", sessionId, "tool", tool, "arguments", redactor.map(arguments)));
        if (!Boolean.TRUE.equals(result.get("found"))) return null;
        return new ToolReplay(result.get("output") == null ? null : String.valueOf(result.get("output")),
                castMap(result.get("parsed")));
    }

    @Override
    public void save(String sessionId, String tool, Map<String, Object> arguments, String output,
                     Map<String, Object> parsed) {
        client.call("runtime.save_replay", Map.of("session_id", sessionId, "tool", tool,
                "arguments", redactor.map(arguments), "output", text(output),
                "parsed", redactor.value(parsed)));
    }

    @Override
    public void invalidate(String sessionId) {
        client.call("runtime.invalidate_replay", Map.of("session_id", sessionId));
    }

    @Override
    public Optional<List<Map<String, Object>>> loadHistory(UUID userId, String sessionId, int limit) {
        Map<String, Object> result = client.call("runtime.load_history", Map.of("owner_id", userId.toString(),
                "session_id", sessionId, "limit", limit));
        return Optional.of(list(result));
    }

    @Override
    public Map<String, Object> findPending(String sessionId, String tool, Map<String, Object> arguments) {
        Map<String, Object> result = client.call("runtime.find_pending", Map.of("session_id", sessionId,
                "tool", tool, "arguments", arguments));
        return Boolean.TRUE.equals(result.get("found")) ? castMap(result.get("pending")) : null;
    }

    @Override
    public void savePending(String sessionId, Map<String, Object> pending) {
        client.call("runtime.save_pending", Map.of("session_id", sessionId, "pending", pending));
    }

    @Override
    public void clearPending(String sessionId) {
        client.call("runtime.clear_pending", Map.of("session_id", sessionId));
    }

    @Override
    public boolean consumeNonce(String sessionId, String nonce) {
        return Boolean.TRUE.equals(client.call("runtime.consume_nonce", Map.of(
                "session_id", sessionId, "nonce", nonce)).get("consumed"));
    }

    @Override
    public void appendUser(String sessionId, String content) {
        client.call("runtime.append_user", Map.of("session_id", sessionId, "content", text(content)));
    }

    @Override
    public boolean appendContextIfChanged(String sessionId, String source, String kind, String content) {
        return Boolean.TRUE.equals(client.call("runtime.append_context", Map.of("session_id", sessionId,
                "source", text(source), "kind", text(kind), "content", text(content))).get("inserted"));
    }

    @Override
    public void appendAssistant(String sessionId, String content, String reasoning) {
        client.call("runtime.append_assistant", Map.of("session_id", sessionId,
                "content", text(content), "reasoning", text(reasoning)));
    }

    @Override
    public void appendToolTrace(String sessionId, String tool, Map<String, Object> arguments,
                                String output, Map<String, Object> parsed) {
        client.call("runtime.append_tool", Map.of("session_id", sessionId, "tool", tool,
                "arguments", redactor.map(arguments), "output", text(output),
                "parsed", redactor.value(parsed)));
    }

    @Override
    public void updateLastTrace(String sessionId, List<Map<String, Object>> traces) {
        client.call("runtime.update_trace", Map.of("session_id", sessionId, "traces", redactor.value(traces == null ? List.of() : traces)));
    }

    @Override
    public void updateContextUsage(String sessionId, Map<String, Object> usage) {
        if (usage != null) client.call("runtime.update_usage", Map.of("session_id", sessionId, "usage", usage));
    }

    @Override
    public void start(String sessionId) { client.call("runtime.start", Map.of("session_id", sessionId)); }

    @Override
    public void update(String sessionId, String status, String phase) {
        client.call("runtime.update", Map.of("session_id", sessionId, "status", status, "phase", phase == null ? "" : phase));
    }

    @Override
    public Map<String, Object> find(String sessionId) {
        return client.call("runtime.find", Map.of("session_id", sessionId));
    }

    @Override
    public void markInterrupted() { client.call("runtime.interrupt", Map.of()); }

    @Override
    public void appendEvent(String sessionId, String event, Map<String, Object> data) {
        client.call("runtime.append_event", Map.of("session_id", sessionId, "event", text(event),
                "data", redactor.value(data)));
    }

    @Override
    public List<ChatRunStateStore.RunEvent> loadEvents(String sessionId, int limit) {
        Object raw = client.call("runtime.events", Map.of("session_id", sessionId, "limit", limit)).get("items");
        if (!(raw instanceof List<?> items)) return List.of();
        List<ChatRunStateStore.RunEvent> events = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> value = castMap(item);
            Object id = value.get("id");
            if (!(id instanceof Number number)) continue;
            events.add(new ChatRunStateStore.RunEvent(number.longValue(), String.valueOf(value.get("event")),
                    castMap(value.get("data"))));
        }
        return events;
    }

    private List<Map<String, Object>> list(Map<String, Object> result) {
        Object value = result.get("items");
        if (!(value instanceof List<?> items)) return List.of();
        return items.stream().map(RemoteChatRuntimeStateStore::castMap).toList();
    }

    private static Map<String, Object> castMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private String text(String value) {
        String result = redactor.text(value);
        return result == null ? "" : result;
    }
}
