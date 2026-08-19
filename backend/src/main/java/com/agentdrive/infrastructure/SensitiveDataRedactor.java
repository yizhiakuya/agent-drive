package com.agentdrive.infrastructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 对会话、工具参数和日志中的敏感内容做不可逆脱敏。
 * <p>文本按模式替换 OpenAI/Jina key 和 Bearer 凭据；Map 的敏感键直接遮蔽值，
 * List 和嵌套 Map 递归处理，适合在落库前调用。</p>
 */
public final class SensitiveDataRedactor {
    private static final Pattern OPENAI_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern JINA_KEY = Pattern.compile("\\bjina_[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}");

    /**
     * 替换文本中的已知凭据模式。
     * @param value 原始文本；空值和空字符串原样返回。
     * @return 将 key 替换为 {@code [REDACTED]}、Bearer token 替换为 {@code Bearer [REDACTED]} 后的文本。
     */
    public String text(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return BEARER.matcher(JINA_KEY.matcher(OPENAI_KEY.matcher(value)
                .replaceAll("[REDACTED]"))
                .replaceAll("[REDACTED]"))
                .replaceAll("Bearer [REDACTED]");
    }

    /**
     * 按运行时类型递归脱敏一个值。
     * @param value 待处理的字符串、Map、List 或普通对象。
     * @return 与原结构对应的脱敏副本；敏感键的值为 {@code ***}，普通对象原样返回。
     */
    public Object value(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, item) -> {
                String name = String.valueOf(key);
                result.put(name, isSecretKey(name) ? "***" : value(item));
            });
            return result;
        }
        if (value instanceof List<?> source) {
            List<Object> result = new ArrayList<>();
            source.forEach(item -> result.add(value(item)));
            return result;
        }
        if (value instanceof String string) {
            return text(string);
        }
        return value;
    }

    /**
     * 脱敏一个 Map，并保证返回值具有字符串键。
     * @param value 原始参数 Map；{@code null} 按空 Map 处理。
     * @return 递归脱敏后的有序 Map，不修改调用方传入的 Map。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> map(Map<String, Object> value) {
        Object redacted = value(value == null ? Map.of() : value);
        return (Map<String, Object>) redacted;
    }

    /**
     * 判断字段名是否代表不应回显的凭据。
     * @param key 待判断的字段名；空值按空名称处理。
     * @return 字段名包含 key、token、secret、password、authorization 或 cookie（不区分大小写）时为 {@code true}。
     */
    public static boolean isSecretKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return normalized.contains("key") || normalized.contains("token")
                || normalized.contains("secret") || normalized.contains("password")
                || normalized.contains("authorization") || normalized.contains("cookie");
    }
}
