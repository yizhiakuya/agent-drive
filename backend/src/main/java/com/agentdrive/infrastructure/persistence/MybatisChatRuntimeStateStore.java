package com.agentdrive.infrastructure.persistence;

import com.agentdrive.agent.ChatTranscriptStore;
import com.agentdrive.agent.ConfirmationStateStore;
import com.agentdrive.infrastructure.PersistentChatRuntimeStateStore;
import com.agentdrive.infrastructure.SensitiveDataRedactor;
import com.agentdrive.agent.ToolReplayStore;
import com.agentdrive.infrastructure.persistence.mapper.ChatRuntimeStateMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 将 Agent 运行时状态持久化到 PostgreSQL/MyBatis。
 * <p>负责工具重放、待确认调用、消息、last trace 和 nonce 消费；写入消息及 trace 前统一经过
 * {@link SensitiveDataRedactor}，而待确认参数保持原文以支持签名校验和确定性重放。</p>
 */
public class MybatisChatRuntimeStateStore implements PersistentChatRuntimeStateStore {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final ChatRuntimeStateMapper mapper;
    private final ObjectMapper objectMapper;
    private final SensitiveDataRedactor redactor;

    /**
     * 使用默认脱敏器创建运行时状态存储。
     * @param mapper 读写运行时状态表的 MyBatis Mapper。
     * @param objectMapper 编解码参数、工具结果和 trace JSON 的映射器。
     */
    public MybatisChatRuntimeStateStore(ChatRuntimeStateMapper mapper, ObjectMapper objectMapper) {
        this(mapper, objectMapper, new SensitiveDataRedactor());
    }

    /**
     * 保存运行时状态依赖。
     * @param mapper 读写 replay、pending、message、trace 和 nonce 的 Mapper。
     * @param objectMapper 把 Map/List 转为数据库 JSON 的映射器。
     * @param redactor 落库前清理文本和结构化值的脱敏器。
     */
    public MybatisChatRuntimeStateStore(ChatRuntimeStateMapper mapper,
                                        ObjectMapper objectMapper,
                                        SensitiveDataRedactor redactor) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.redactor = Objects.requireNonNull(redactor, "redactor must not be null");
    }

    /**
     * 按 session、工具名和精确参数 JSON 查找可复用的工具结果。
     * @param sessionId 会话 UUID 文本；非法 UUID 不访问数据库。
     * @param tool 工具名称。
     * @param arguments 本次调用参数，会按 JSON 编码参与匹配。
     * @return 已保存的输出和 parsed 结果；没有精确匹配时为 {@code null}。
     */
    @Override
    public ToolReplayStore.ToolReplay find(String sessionId, String tool, Map<String, Object> arguments) {
        if (parseUuid(sessionId) == null || tool == null) {
            return null;
        }
        Map<String, Object> row = mapper.selectToolReplay(sessionId, tool, json(arguments));
        if (row == null) {
            return null;
        }
        return new ToolReplayStore.ToolReplay(
                row.get("output") == null ? null : String.valueOf(row.get("output")),
                readMap(row.get("parsed"))
        );
    }

    /**
     * 保存一次工具调用的重放结果。
     * @param sessionId 会话 UUID 文本；非法 UUID 时忽略写入。
     * @param tool 工具名称。
     * @param arguments 精确重放所需的参数。
     * @param output 工具原始输出。
     * @param parsed 工具输出解析后的结构化结果。
     */
    @Override
    public void save(String sessionId, String tool, Map<String, Object> arguments,
                     String output, Map<String, Object> parsed) {
        if (parseUuid(sessionId) == null || tool == null) {
            return;
        }
        mapper.insertToolReplay(sessionId, tool, json(arguments), output, json(parsed));
    }

    /**
     * 读取待确认调用，并要求工具名和参数与当前请求完全相等。
     * @param sessionId 会话 UUID 文本。
     * @param tool 当前待确认的工具名。
     * @param arguments 当前请求参数。
     * @return 精确匹配的 pending Map；不匹配、无记录或 ID 非法时为 {@code null}。
     */
    @Override
    public Map<String, Object> findPending(String sessionId, String tool, Map<String, Object> arguments) {
        if (parseUuid(sessionId) == null || tool == null) {
            return null;
        }
        Map<String, Object> pending = readMap(mapper.selectPending(sessionId));
        if (pending == null || !tool.equals(pending.get("tool"))) {
            return null;
        }
        if (!Objects.equals(arguments, mapValue(pending.get("arguments")))) {
            return null;
        }
        return new LinkedHashMap<>(pending);
    }

    /**
     * 更新会话的 pending confirmation JSON。
     * @param sessionId 会话 UUID 文本；非法 ID 时忽略。
     * @param pending 要保留原文的确认参数、签名和状态。
     */
    @Override
    public void savePending(String sessionId, Map<String, Object> pending) {
        if (parseUuid(sessionId) == null || pending == null) {
            return;
        }
        mapper.updatePending(sessionId, json(pending));
    }

    /**
     * 清除会话的 pending confirmation。
     * @param sessionId 会话 UUID 文本；非法 ID 时忽略。
     */
    @Override
    public void clearPending(String sessionId) {
        if (parseUuid(sessionId) == null) {
            return;
        }
        mapper.clearPending(sessionId);
    }

    /**
     * 脱敏后追加一条 user 消息。
     * @param sessionId 会话 UUID 文本。
     * @param content 用户原始消息正文。
     */
    @Override
    public void appendUser(String sessionId, String content) {
        insertMessage(sessionId, "user", redactor.text(content), null, null, null, null);
    }

    /**
     * 脱敏后追加 assistant 正文和独立 reasoning 字段。
     * @param sessionId 会话 UUID 文本。
     * @param content assistant 正文。
     * @param reasoning provider 返回的思考片段，可为空。
     */
    @Override
    public void appendAssistant(String sessionId, String content, String reasoning) {
        insertMessage(sessionId, "assistant", redactor.text(content), redactor.text(reasoning),
                null, null, null);
    }

    /**
     * 脱敏后追加工具调用 trace，同时保存工具名、参数 JSON 和 parsed 结果。
     * @param sessionId 会话 UUID 文本。
     * @param tool 工具名称。
     * @param arguments 工具参数 Map，会递归遮蔽敏感键。
     * @param output 工具原始输出，会按文本模式清理凭据。
     * @param parsed 工具解析结果，会递归遮蔽敏感值。
     */
    @Override
    public void appendToolTrace(String sessionId, String tool, Map<String, Object> arguments,
                                String output, Map<String, Object> parsed) {
        insertMessage(sessionId, "tool_call", redactor.text(output), null, tool,
                json(redactor.map(arguments)), json(redactor.value(parsed)));
    }

    /**
     * 脱敏并覆盖会话的 last_trace JSON。
     * @param sessionId 会话 UUID 文本。
     * @param traces 当前请求的工具轨迹；空值按空列表保存。
     */
    @Override
    public void updateLastTrace(String sessionId, List<Map<String, Object>> traces) {
        if (parseUuid(sessionId) == null) {
            return;
        }
        mapper.updateLastTrace(sessionId, json(redactor.value(traces == null ? List.of() : traces)));
    }

    /**
     * 向消息表插入一行；调用方已完成文本和结构化数据的脱敏。
     * @param sessionId 会话 UUID 文本。
     * @param role 消息角色，例如 user、assistant 或 tool_call。
     * @param content 要保存的正文或工具输出。
     * @param reasoning assistant 的独立 reasoning 文本。
     * @param tool 工具名称；普通消息为空。
     * @param arguments 工具参数 JSON 文本。
     * @param parsed 工具解析结果 JSON 文本。
     */
    private void insertMessage(String sessionId, String role, String content, String reasoning,
                               String tool, String arguments, String parsed) {
        if (parseUuid(sessionId) == null) {
            return;
        }
        mapper.insertMessage(sessionId, role, content, reasoning, tool, arguments, parsed);
    }

    /**
     * 原子消费一次性 nonce，防止确认请求重放。
     * @param sessionId 会话 UUID 文本。
     * @param nonce 待消费的随机 nonce。
     * @return 当前调用恰好消费一行时为 {@code true}；参数非法或已消费时为 {@code false}。
     */
    @Override
    public boolean consumeNonce(String sessionId, String nonce) {
        if (parseUuid(sessionId) == null || nonce == null || nonce.isBlank()) {
            return false;
        }
        return mapper.consumeNonce(sessionId, nonce) == 1;
    }

    /**
     * 使用 Jackson 将运行时状态编码为 JSON。
     * @param value 待编码的 Map、List 或标量；空值按空对象编码。
     * @return JSON 文本。
     * @throws IllegalArgumentException 值无法序列化时抛出。
     */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Unable to encode chat runtime state", error);
        }
    }

    /**
     * 把数据库 JSON 列或 Map 读取为字符串键 Map。
     * @param value 数据库返回的 JSON 文本或 Map。
     * @return 有序 Map；空值/空文本返回 {@code null}。
     * @throws IllegalStateException 已保存 JSON 无法解析时抛出。
     */
    private Map<String, Object> readMap(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> source) {
            return mapValue(source);
        }
        String raw = String.valueOf(value);
        if (raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, MAP_TYPE);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid JSON stored in chat runtime state", error);
        }
    }

    /**
     * 将任意值转成参数比较所需的 Map。
     * @param value 可能是 Map 的对象。
     * @return Map 输入的字符串键副本，否则返回空 Map。
     */
    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        return mapValue(source);
    }

    /**
     * 复制 Map 并把键统一转换成字符串。
     * @param source 原始 Map。
     * @return 保持迭代顺序的字符串键副本。
     */
    private static Map<String, Object> mapValue(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    /**
     * 解析会话 ID，避免非法输入进入 MyBatis SQL。
     * @param value 会话 UUID 文本。
     * @return 合法 UUID；空白或格式错误时为 {@code null}。
     */
    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
