package com.agentdrive.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用于测试的进程内工具重放缓存。
 *
 * <p>键由 sessionId、tool 和按 key 排序后的参数 JSON 组成；命中后返回之前保存的
 * 原始输出与解析结果，避免重复执行副作用。该缓存不跨进程、不持久化，不能作为生产
 * 任务状态源。</p>
 */
public final class InMemoryToolReplayStore implements ToolReplayStore {
    private final ObjectMapper mapper;
    private final Map<Key, ToolReplay> entries = new ConcurrentHashMap<>();

    /**
     * 保存一个开启 Map key 排序的 mapper，用于生成稳定缓存键。
     * @param mapper 工具参数规范化所用的 Jackson mapper
     */
    public InMemoryToolReplayStore(ObjectMapper mapper) {
        this.mapper = mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /**
     * 查找同一会话、工具和参数的历史执行结果。
     * @param sessionId 会话标识；空值不会参与缓存
     * @param tool 工具名
     * @param arguments 工具参数
     * @return 缓存的输出和解析结果；未命中时返回 null
     */
    @Override
    public ToolReplay find(String sessionId, String tool, Map<String, Object> arguments) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return entries.get(key(sessionId, tool, arguments == null ? Map.of() : arguments));
    }

    /**
     * 保存一次工具执行的可重放结果；相同键会覆盖旧结果。
     * @param sessionId 会话标识；为空时忽略保存
     * @param tool 工具名
     * @param arguments 原始工具参数
     * @param output 工具原始输出
     * @param parsed 从输出解析的结构化结果
     */
    @Override
    public void save(String sessionId, String tool, Map<String, Object> arguments, String output,
                     Map<String, Object> parsed) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        entries.put(key(sessionId, tool, arguments == null ? Map.of() : arguments), new ToolReplay(
                redactText(output), castMap(redactValue(parsed))));
    }

    /** {@inheritDoc} */
    @Override
    public void invalidate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        entries.keySet().removeIf(entry -> sessionId.equals(entry.sessionId()));
    }

    /**
     * 生成重放缓存的确定性键。
     * @param sessionId 会话标识
     * @param tool 工具名
     * @param arguments 工具参数；null 按空 Map 序列化
     * @return 由会话、工具和规范化参数 JSON 组成的键
     * @throws IllegalArgumentException 参数无法序列化时抛出
     */
    private Key key(String sessionId, String tool, Map<String, Object> arguments) {
        try {
            return new Key(sessionId, tool, mapper.writeValueAsString(arguments == null ? Map.of() : arguments));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Unable to canonicalize tool arguments", error);
        }
    }

    private Map<String, Object> redactMap(Map<String, Object> value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) redactValue(value);
        return result;
    }

    private Object redactValue(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, item) -> {
                String name = String.valueOf(key);
                result.put(name, isSecret(name) ? "***" : redactValue(item));
            });
            return result;
        }
        if (value instanceof List<?> source) return source.stream().map(this::redactValue).toList();
        return value;
    }

    private Map<String, Object> castMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private String redactText(String value) {
        if (value == null) return null;
        return value.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]{8,}", "Bearer [REDACTED]")
                .replaceAll("\\b(?:sk-[A-Za-z0-9_-]{8,}|jina_[A-Za-z0-9_-]{8,})\\b", "[REDACTED]");
    }

    private boolean isSecret(String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("key") || normalized.contains("token")
                || normalized.contains("secret") || normalized.contains("password")
                || normalized.contains("authorization") || normalized.contains("cookie");
    }

    /**
     * 工具重放缓存的复合键。
     *
     * @param sessionId 会话分区
     * @param tool 工具名
     * @param arguments 按稳定 JSON 编码的参数
     */
    private record Key(String sessionId, String tool, String arguments) {
    }
}
