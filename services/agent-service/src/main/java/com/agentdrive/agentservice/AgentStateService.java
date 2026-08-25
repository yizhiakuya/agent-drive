package com.agentdrive.agentservice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent Service 的 owner-scoped session/transcript/run state 用例。
 * 请求只通过内部网关到达；所有 SQL 都再次按 session 和 owner 约束。
 */
@Service
public class AgentStateService {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AgentServiceProperties properties;

    public AgentStateService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                             AgentServiceProperties properties) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Map<String, Object> ready() {
        try {
            Integer sessions = jdbc.queryForObject("SELECT COUNT(*) FROM agent_sessions", Integer.class);
            return Map.of("ready", !properties.internalToken().isBlank(), "service", "agent",
                    "sessions", sessions == null ? 0 : sessions);
        } catch (RuntimeException error) {
            return Map.of("ready", false, "service", "agent", "error", "database_unavailable");
        }
    }

    @Transactional
    public Map<String, Object> handle(Map<String, Object> request) {
        String action = text(request.get("action"));
        return switch (action) {
            case "session.create" -> createSession(request);
            case "session.find" -> findSession(request);
            case "session.list" -> listSessions(request);
            case "session.details" -> sessionDetails(request);
            case "session.messages" -> sessionMessages(request);
            case "session.delete" -> deleteSession(request);
            case "session.update_summary" -> updateSummary(request);
            case "session.import" -> importSession(request);
            case "runtime.find_replay" -> findReplay(request);
            case "runtime.save_replay" -> saveReplay(request);
            case "runtime.import_replay" -> saveReplay(request);
            case "runtime.invalidate_replay" -> invalidateReplay(request);
            case "runtime.load_history" -> loadHistory(request);
            case "runtime.loaded_skills" -> loadedSkills(request);
            case "runtime.append_user" -> appendMessage(request, "user", null, null, null, null, null);
            case "runtime.append_context" -> appendContext(request);
            case "runtime.append_assistant" -> appendMessage(request, "assistant",
                    text(request.get("content")), text(request.get("reasoning")), null, null, null);
            case "runtime.append_tool" -> appendMessage(request, "tool_call",
                    text(request.get("output")), null, text(request.get("tool")),
                    json(request.get("arguments")), json(request.get("parsed")));
            case "runtime.import_message" -> importMessage(request);
            case "runtime.update_trace" -> updateTrace(request);
            case "runtime.update_usage" -> updateUsage(request);
            case "runtime.start" -> updateRun(request, "running", "starting");
            case "runtime.update" -> updateRun(request, text(request.get("status")), text(request.get("phase")));
            case "runtime.find" -> findRun(request);
            case "runtime.interrupt" -> interruptRuns();
            case "runtime.append_event" -> appendEvent(request);
            case "runtime.import_event" -> appendEvent(request);
            case "runtime.events" -> loadEvents(request);
            case "runtime.find_pending" -> findPending(request);
            case "runtime.save_pending" -> savePending(request);
            case "runtime.clear_pending" -> clearPending(request);
            case "runtime.consume_nonce" -> consumeNonce(request);
            case "runtime.import_nonce" -> consumeNonce(request);
            default -> throw new AgentStateException(400, "unknown_action", "agent state action is not supported");
        };
    }

    private Map<String, Object> createSession(Map<String, Object> request) {
        UUID owner = uuid(request.get("owner_id"), "owner_id");
        UUID session = request.get("session_id") == null
                ? UUID.randomUUID() : uuid(request.get("session_id"), "session_id");
        jdbc.update("""
                INSERT INTO agent_sessions(id, owner_id) VALUES (?, ?)
                ON CONFLICT (id) DO UPDATE SET owner_id = EXCLUDED.owner_id,
                  updated_at = CURRENT_TIMESTAMP
                """, session, owner);
        return Map.of("id", session.toString(), "user_id", owner.toString());
    }

    private Map<String, Object> findSession(Map<String, Object> request) {
        UUID owner = uuid(request.get("owner_id"), "owner_id");
        UUID session = uuid(request.get("session_id"), "session_id");
        Map<String, Object> row = jdbc.query("SELECT id, owner_id FROM agent_sessions WHERE id = ? AND owner_id = ?",
                rs -> rs.next() ? Map.of("id", rs.getObject("id").toString(),
                        "user_id", rs.getObject("owner_id").toString()) : null, session, owner);
        return row == null ? Map.of("found", false) : Map.of("found", true, "session", row);
    }

    private Map<String, Object> listSessions(Map<String, Object> request) {
        UUID owner = uuid(request.get("owner_id"), "owner_id");
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT id, owner_id, title, summary, status, phase, run_state, context_usage, created_at, updated_at
                FROM agent_sessions WHERE owner_id = ? ORDER BY updated_at DESC
                """, (rs, row) -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", rs.getObject("id").toString());
            value.put("user_id", rs.getObject("owner_id").toString());
            value.put("title", rs.getString("title"));
            value.put("summary", rs.getString("summary"));
            value.put("status", rs.getString("status"));
            value.put("phase", rs.getString("phase"));
            value.put("run_state", read(rs.getString("run_state")));
            value.put("context_usage", readNullable(rs.getString("context_usage")));
            value.put("created_at", rs.getObject("created_at"));
            value.put("updated_at", rs.getObject("updated_at"));
            return value;
        }, owner);
        return Map.of("items", rows);
    }

    private Map<String, Object> sessionDetails(Map<String, Object> request) {
        UUID owner = uuid(request.get("owner_id"), "owner_id");
        UUID session = uuid(request.get("session_id"), "session_id");
        Map<String, Object> value = jdbc.query("""
                SELECT id, owner_id, title, summary, status, phase, run_state, last_trace, pending_confirmation,
                       context_usage, created_at, updated_at
                FROM agent_sessions WHERE id = ? AND owner_id = ?
                """, rs -> rs.next() ? sessionMap(rs) : null, session, owner);
        return value == null ? Map.of("found", false) : Map.of("found", true, "session", value);
    }

    private Map<String, Object> sessionMessages(Map<String, Object> request) {
        UUID owner = uuid(request.get("owner_id"), "owner_id");
        UUID session = ownedSession(request, owner);
        List<Map<String, Object>> messages = jdbc.query("""
                SELECT role, content, reasoning, tool_name, arguments_json, parsed_json,
                       context_source, context_kind, created_at
                FROM agent_messages WHERE session_id = ? AND owner_id = ? ORDER BY id
                """, (rs, row) -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("role", rs.getString("role"));
            value.put("content", rs.getString("content"));
            if (rs.getString("reasoning") != null) value.put("reasoning", rs.getString("reasoning"));
            if (rs.getString("tool_name") != null) value.put("tool", rs.getString("tool_name"));
            if (rs.getString("context_source") != null) value.put("context_source", rs.getString("context_source"));
            if (rs.getString("context_kind") != null) value.put("context_kind", rs.getString("context_kind"));
            if (rs.getString("arguments_json") != null) value.put("arguments", readAny(rs.getString("arguments_json")));
            if (rs.getString("parsed_json") != null) value.put("parsed", readAny(rs.getString("parsed_json")));
            value.put("ts", rs.getObject("created_at"));
            return value;
        }, session, owner);
        return Map.of("items", messages);
    }

    private Map<String, Object> deleteSession(Map<String, Object> request) {
        UUID owner = uuid(request.get("owner_id"), "owner_id");
        UUID session = uuid(request.get("session_id"), "session_id");
        return Map.of("deleted", jdbc.update("DELETE FROM agent_sessions WHERE id = ? AND owner_id = ?", session, owner) > 0);
    }

    private Map<String, Object> updateSummary(Map<String, Object> request) {
        UUID owner = uuid(request.get("owner_id"), "owner_id");
        UUID session = ownedSession(request, owner);
        int updated = jdbc.update("UPDATE agent_sessions SET summary = ?, title = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND owner_id = ?",
                text(request.get("summary")), text(request.get("title")), session, owner);
        return Map.of("updated", updated > 0);
    }

    /** 迁移期保留既有 session UUID 和摘要字段。 */
    private Map<String, Object> importSession(Map<String, Object> request) {
        UUID owner = uuid(request.get("owner_id"), "owner_id");
        UUID session = uuid(request.get("session_id"), "session_id");
        String status = text(request.get("status"));
        String phase = text(request.get("phase"));
        jdbc.update("""
                INSERT INTO agent_sessions(id, owner_id, title, summary, status, phase, run_state,
                    last_trace, pending_confirmation, context_usage)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET owner_id = EXCLUDED.owner_id, title = EXCLUDED.title,
                    summary = EXCLUDED.summary, status = EXCLUDED.status, phase = EXCLUDED.phase,
                    run_state = EXCLUDED.run_state, last_trace = EXCLUDED.last_trace,
                    pending_confirmation = EXCLUDED.pending_confirmation, context_usage = EXCLUDED.context_usage,
                    updated_at = CURRENT_TIMESTAMP
                """, session, owner, nullable(request.get("title")), nullable(request.get("summary")),
                status.isBlank() ? "idle" : status, phase.isBlank() ? "idle" : phase,
                json(request.get("run_state")), json(request.get("last_trace")),
                nullableJson(request.get("pending_confirmation")), nullableJson(request.get("context_usage")));
        return Map.of("imported", true, "id", session.toString());
    }

    private Map<String, Object> findReplay(Map<String, Object> request) {
        UUID session = uuid(request.get("session_id"), "session_id");
        String tool = required(request.get("tool"), "tool");
        String arguments = json(request.get("arguments"));
        return jdbc.query("SELECT output, parsed_json FROM agent_replays WHERE session_id = ? AND tool_name = ? AND arguments_json = ?",
                rs -> rs.next() ? mapOfNullable("found", true, "output", rs.getString("output"),
                        "parsed", readNullable(rs.getString("parsed_json"))) : Map.of("found", false),
                session, tool, arguments);
    }

    private Map<String, Object> saveReplay(Map<String, Object> request) {
        UUID session = uuid(request.get("session_id"), "session_id");
        String tool = required(request.get("tool"), "tool");
        jdbc.update("""
                INSERT INTO agent_replays(session_id, tool_name, arguments_json, output, parsed_json)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (session_id, tool_name, arguments_json)
                DO UPDATE SET output = EXCLUDED.output, parsed_json = EXCLUDED.parsed_json,
                              created_at = CURRENT_TIMESTAMP
                """, session, tool, json(request.get("arguments")), text(request.get("output")),
                json(request.get("parsed")));
        return Map.of("saved", true);
    }

    private Map<String, Object> invalidateReplay(Map<String, Object> request) {
        UUID session = uuid(request.get("session_id"), "session_id");
        return Map.of("deleted", jdbc.update("DELETE FROM agent_replays WHERE session_id = ?", session));
    }

    private Map<String, Object> loadHistory(Map<String, Object> request) {
        UUID owner = uuid(request.get("owner_id"), "owner_id");
        UUID session = ownedSession(request, owner);
        int limit = bounded(request.get("limit"), 200);
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT role, content FROM agent_messages WHERE session_id = ? AND owner_id = ?
                AND role IN ('user', 'assistant', 'context') ORDER BY id DESC LIMIT ?
                """, (rs, row) -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("role", rs.getString("role"));
                    value.put("content", rs.getString("content"));
                    return value;
                },
                session, owner, limit);
        List<Map<String, Object>> ordered = new ArrayList<>(rows);
        java.util.Collections.reverse(ordered);
        return Map.of("items", ordered);
    }

    private Map<String, Object> loadedSkills(Map<String, Object> request) {
        UUID session = uuid(request.get("session_id"), "session_id");
        List<String> names = jdbc.query("SELECT arguments_json FROM agent_messages WHERE session_id = ? AND tool_name = 'read_skill' ORDER BY id",
                (rs, row) -> {
                    Map<String, Object> args = read(rs.getString("arguments_json"));
                    String name = text(args.get("name"));
                    return name.isBlank() ? text(args.get("skill")) : name;
                }, session).stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
        return Map.of("items", names);
    }

    private Map<String, Object> appendContext(Map<String, Object> request) {
        UUID session = uuid(request.get("session_id"), "session_id");
        String source = required(request.get("source"), "source");
        String kind = required(request.get("kind"), "kind");
        String content = text(request.get("content"));
        Integer previous = jdbc.queryForObject("SELECT COUNT(*) FROM agent_messages WHERE session_id = ? AND role = 'context' AND context_source = ? AND context_kind = ? AND content = ?",
                Integer.class, session, source, kind, content);
        if (previous != null && previous > 0) return Map.of("inserted", false);
        appendMessage(request, "context", content, null, null, null, null);
        return Map.of("inserted", true);
    }

    private Map<String, Object> appendMessage(Map<String, Object> request, String role, String content,
                                              String reasoning, String tool, String arguments, String parsed) {
        UUID session = uuid(request.get("session_id"), "session_id");
        Map<String, Object> owner = jdbc.query("SELECT owner_id FROM agent_sessions WHERE id = ?",
                rs -> rs.next() ? Map.of("owner_id", rs.getObject("owner_id")) : null, session);
        if (owner == null) throw new AgentStateException(404, "session_not_found", "agent session does not exist");
        jdbc.update("""
                INSERT INTO agent_messages(session_id, owner_id, role, content, reasoning, tool_name,
                    arguments_json, parsed_json, context_source, context_kind)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, session, owner.get("owner_id"), role, content,
                reasoning, tool, arguments, parsed, text(request.get("source")), text(request.get("kind")));
        jdbc.update("UPDATE agent_sessions SET updated_at = CURRENT_TIMESTAMP WHERE id = ?", session);
        return Map.of("inserted", true);
    }

    /** 迁移期按原 role/扩展字段导入 transcript，不改变消息顺序语义。 */
    private Map<String, Object> importMessage(Map<String, Object> request) {
        UUID session = uuid(request.get("session_id"), "session_id");
        String role = required(request.get("role"), "role");
        Map<String, Object> owner = jdbc.query("SELECT owner_id FROM agent_sessions WHERE id = ?",
                rs -> rs.next() ? Map.of("owner_id", rs.getObject("owner_id")) : null, session);
        if (owner == null) throw new AgentStateException(404, "session_not_found", "agent session does not exist");
        jdbc.update("""
                INSERT INTO agent_messages(session_id, owner_id, role, content, reasoning, tool_name,
                    arguments_json, parsed_json, context_source, context_kind)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, session, owner.get("owner_id"), role, nullable(request.get("content")),
                nullable(request.get("reasoning")), nullable(request.get("tool")),
                nullableJson(request.get("arguments")), nullableJson(request.get("parsed")),
                nullable(request.get("context_source")), nullable(request.get("context_kind")));
        return Map.of("imported", true);
    }

    private Map<String, Object> updateTrace(Map<String, Object> request) {
        UUID session = uuid(request.get("session_id"), "session_id");
        jdbc.update("UPDATE agent_sessions SET last_trace = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                json(request.get("traces")), session);
        return Map.of("updated", true);
    }

    private Map<String, Object> updateUsage(Map<String, Object> request) {
        UUID session = uuid(request.get("session_id"), "session_id");
        jdbc.update("UPDATE agent_sessions SET context_usage = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                json(request.get("usage")), session);
        return Map.of("updated", true);
    }

    private Map<String, Object> updateRun(Map<String, Object> request, String status, String phase) {
        UUID session = uuid(request.get("session_id"), "session_id");
        if (status == null || status.isBlank()) throw new AgentStateException(400, "status_missing", "run status is required");
        Map<String, Object> state = findRunFor(session);
        state.put("status", status);
        state.put("phase", phase == null || phase.isBlank() ? status : phase);
        state.put("updated_at", Instant.now().toString());
        jdbc.update("UPDATE agent_sessions SET status = ?, phase = ?, run_state = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                status, phase == null || phase.isBlank() ? status : phase, json(state), session);
        return Map.of("updated", true, "status", status, "phase", phase == null ? status : phase);
    }

    private Map<String, Object> findRun(Map<String, Object> request) {
        UUID session = uuid(request.get("session_id"), "session_id");
        return findRunFor(session);
    }

    private Map<String, Object> findRunFor(UUID session) {
        return jdbc.query("SELECT status, phase, run_state FROM agent_sessions WHERE id = ?",
                rs -> {
                    if (!rs.next()) return new LinkedHashMap<>();
                    Map<String, Object> value = new LinkedHashMap<>(read(rs.getString("run_state")));
                    value.putIfAbsent("status", rs.getString("status"));
                    value.putIfAbsent("phase", rs.getString("phase"));
                    return value;
                }, session);
    }

    private Map<String, Object> interruptRuns() {
        int updated = jdbc.update("UPDATE agent_sessions SET status = 'interrupted', phase = 'process_restart', updated_at = CURRENT_TIMESTAMP WHERE status = 'running'");
        return Map.of("interrupted", updated);
    }

    private Map<String, Object> appendEvent(Map<String, Object> request) {
        UUID session = uuid(request.get("session_id"), "session_id");
        Number id = jdbc.queryForObject("INSERT INTO agent_events(session_id, event_name, event_data) VALUES (?, ?, ?) RETURNING id",
                Number.class, session, required(request.get("event"), "event"), json(request.get("data")));
        return Map.of("id", id == null ? 0 : id.longValue());
    }

    private Map<String, Object> loadEvents(Map<String, Object> request) {
        UUID session = uuid(request.get("session_id"), "session_id");
        int limit = Math.max(1, Math.min(bounded(request.get("limit"), properties.maxEventRows()), properties.maxEventRows()));
        List<Map<String, Object>> rows = jdbc.query("SELECT id, event_name, event_data FROM agent_events WHERE session_id = ? ORDER BY id LIMIT ?",
                (rs, row) -> Map.of("id", rs.getLong("id"), "event", rs.getString("event_name"),
                        "data", read(rs.getString("event_data"))), session, limit);
        return Map.of("items", rows);
    }

    private Map<String, Object> findPending(Map<String, Object> request) {
        UUID session = uuid(request.get("session_id"), "session_id");
        Map<String, Object> row = jdbc.query("SELECT pending_confirmation FROM agent_sessions WHERE id = ?",
                rs -> rs.next() ? readNullable(rs.getString("pending_confirmation")) : null, session);
        if (row == null || row.isEmpty()) return Map.of("found", false);
        if (!text(request.get("tool")).equals(text(row.get("tool")))
                || !json(request.get("arguments")).equals(json(row.get("arguments")))) {
            return Map.of("found", false);
        }
        return Map.of("found", true, "pending", row);
    }

    private Map<String, Object> savePending(Map<String, Object> request) {
        UUID session = uuid(request.get("session_id"), "session_id");
        jdbc.update("UPDATE agent_sessions SET pending_confirmation = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                json(request.get("pending")), session);
        return Map.of("saved", true);
    }

    private Map<String, Object> clearPending(Map<String, Object> request) {
        UUID session = uuid(request.get("session_id"), "session_id");
        jdbc.update("UPDATE agent_sessions SET pending_confirmation = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?", session);
        return Map.of("cleared", true);
    }

    private Map<String, Object> consumeNonce(Map<String, Object> request) {
        UUID session = uuid(request.get("session_id"), "session_id");
        int inserted = jdbc.update("INSERT INTO agent_nonces(session_id, nonce) VALUES (?, ?) ON CONFLICT DO NOTHING",
                session, required(request.get("nonce"), "nonce"));
        return Map.of("consumed", inserted == 1);
    }

    private UUID ownedSession(Map<String, Object> request, UUID owner) {
        UUID session = uuid(request.get("session_id"), "session_id");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM agent_sessions WHERE id = ? AND owner_id = ?",
                Integer.class, session, owner);
        if (count == null || count == 0) throw new AgentStateException(404, "session_not_found", "agent session does not exist");
        return session;
    }

    private Map<String, Object> sessionMap(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", rs.getObject("id").toString());
        value.put("user_id", rs.getObject("owner_id").toString());
        value.put("title", rs.getString("title"));
        value.put("summary", rs.getString("summary"));
        value.put("status", rs.getString("status"));
        value.put("phase", rs.getString("phase"));
        value.put("run_state", read(rs.getString("run_state")));
        value.put("last_trace", read(rs.getString("last_trace")));
        value.put("pending_confirmation", readNullable(rs.getString("pending_confirmation")));
        value.put("context_usage", readNullable(rs.getString("context_usage")));
        value.put("created_at", rs.getObject("created_at"));
        value.put("updated_at", rs.getObject("updated_at"));
        return value;
    }

    private int bounded(Object value, int max) {
        try { return Math.max(1, Math.min(Integer.parseInt(String.valueOf(value)), max)); }
        catch (RuntimeException ignored) { return max; }
    }

    private UUID uuid(Object value, String name) {
        try { return UUID.fromString(text(value)); }
        catch (IllegalArgumentException error) { throw new AgentStateException(400, "invalid_" + name, name + " is invalid"); }
    }

    private String required(Object value, String name) {
        String result = text(value);
        if (result.isBlank()) throw new AgentStateException(400, name + "_missing", name + " is required");
        return result;
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private Object nullable(Object value) {
        String result = text(value);
        return result.isBlank() ? null : result;
    }

    private Object nullableJson(Object value) {
        return value == null ? null : json(value);
    }

    private String json(Object value) {
        if (value == null) return "{}";
        if (value instanceof String string) return string;
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new AgentStateException(400, "invalid_json", "state JSON is invalid"); }
    }

    private Map<String, Object> read(String value) {
        if (value == null || value.isBlank()) return new LinkedHashMap<>();
        try { return objectMapper.readValue(value, MAP); }
        catch (JsonProcessingException error) { return new LinkedHashMap<>(); }
    }

    private Map<String, Object> readNullable(String value) {
        return value == null || value.isBlank() ? null : read(value);
    }

    private Object readAny(String value) {
        if (value == null || value.isBlank()) return null;
        try { return objectMapper.readValue(value, Object.class); }
        catch (JsonProcessingException error) { return value; }
    }

    private Map<String, Object> mapOfNullable(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    /** Agent Service 业务错误。 */
    public static final class AgentStateException extends RuntimeException {
        private final int status;
        private final String code;
        public AgentStateException(int status, String code, String message) {
            super(message); this.status = status; this.code = code;
        }
        public int status() { return status; }
        public String code() { return code; }
    }
}
