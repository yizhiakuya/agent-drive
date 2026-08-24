package com.agentdrive.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 为高风险 Agent 工具生成并校验一次性确认凭证。
 *
 * <p>凭证把工具名、按键排序后的参数摘要、随机 nonce 和时间戳绑定到 HMAC-SHA256
 * 签名；校验成功后还必须由 {@link ConfirmationStateStore} 原子消费 nonce。因此
 * 同一确认不能被重复重放，展示给用户的消息会遮蔽 key、token、secret 和 password
 * 字段。</p>
 */
public final class ConfirmationService {
    private static final long NONCE_TTL_SECONDS = 600;

    private final byte[] signingKey;
    private final ObjectMapper canonicalMapper;
    private final ConfirmationStateStore stateStore;
    private final SecureRandom random = new SecureRandom();

    /**
     * 使用随机签名密钥和进程内状态存储创建确认服务。
     * @param signingKey 用于 HMAC 的非空密钥；调用方数组会被复制
     * @param objectMapper 用于参数规范化和展示消息 JSON 编码的 mapper
     */
    public ConfirmationService(byte[] signingKey, ObjectMapper objectMapper) {
        this(signingKey, objectMapper, new InMemoryConfirmationStateStore());
    }

    /**
     * 创建使用指定确认状态存储的服务。
     *
     * <p>签名密钥会复制到服务内部；mapper 会复制并开启 map key 排序，保证相同
     * 参数产生确定性的摘要。</p>
     * @param signingKey 用于 HMAC 的非空密钥
     * @param objectMapper 参数规范化和脱敏消息所用的 mapper
     * @param stateStore 保存待确认项并记录已消费 nonce 的存储
     * @throws IllegalArgumentException signingKey 为空时抛出
     * @throws NullPointerException objectMapper 或 stateStore 为空时抛出
     */
    public ConfirmationService(byte[] signingKey, ObjectMapper objectMapper,
                               ConfirmationStateStore stateStore) {
        if (signingKey == null || signingKey.length == 0) {
            throw new IllegalArgumentException("signingKey must not be empty");
        }
        this.signingKey = signingKey.clone();
        this.canonicalMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.stateStore = stateStore;
    }

    /**
     * 创建使用新随机 256 位密钥的确认服务，适合测试或无外部密钥配置的本地运行。
     * @param objectMapper 参数序列化所用的 mapper
     * @return 使用进程内确认状态存储的新服务
     */
    public static ConfirmationService random(ObjectMapper objectMapper) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new ConfirmationService(key, objectMapper);
    }

    /**
     * 为不需要会话隔离的调用生成待确认记录。
     * @param tool 待执行的高风险工具名
     * @param arguments 工具参数；为 null 时按空对象参与签名
     * @return 包含 nonce、时间戳、签名、原始参数和脱敏提示文案的待确认记录
     */
    public Map<String, Object> issue(String tool, Map<String, Object> arguments) {
        return issue(null, tool, arguments);
    }

    /**
     * 为指定会话生成并保存待确认记录。
     *
     * <p>记录中的 arguments 保留原文供后续确定性重放校验，message 则使用脱敏副本
     * 供前端展示。</p>
     * @param sessionId 会话标识；为空时使用默认存储分区
     * @param tool 待执行的高风险工具名
     * @param arguments 工具参数；为 null 时按空对象参与签名
     * @return 已保存的确认记录
     */
    public Map<String, Object> issue(String sessionId, String tool, Map<String, Object> arguments) {
        String nonce = randomNonce();
        long timestamp = System.currentTimeMillis() / 1000;
        String signature = sign(nonce, tool, argsHash(arguments), timestamp);
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("tool", tool);
        pending.put("arguments", arguments == null ? Map.of() : arguments);
        pending.put("nonce", nonce);
        pending.put("ts", timestamp);
        pending.put("signature", signature);
        pending.put("message", "Agent 请求执行高风险操作：" + tool + "(" + redacted(arguments) + ")");
        stateStore.savePending(sessionId, pending);
        return pending;
    }

    /**
     * 生成只供客户端展示/回传的 pending 视图。
     *
     * <p>原始 arguments 继续留在服务端状态存储中参与签名校验；客户端只需要 nonce、
     * 时间戳和签名，不应获得可能包含敏感值的 replay 原文。</p>
     *
     * @param pending 服务端原始待确认记录
     * @return 脱敏后的客户端视图
     */
    public Map<String, Object> publicView(Map<String, Object> pending) {
        if (pending == null) return null;
        Map<String, Object> view = new LinkedHashMap<>(pending);
        view.put("arguments", redactedMap(mapValue(pending.get("arguments"))));
        view.put("message", "Agent 请求执行高风险操作：" + pending.get("tool")
                + "(" + redactedMap(mapValue(pending.get("arguments"))) + ")");
        return view;
    }

    /**
     * 在默认会话分区中验证确认并消费 nonce。
     * @param pending 待执行记录，通常来自 {@link #issue(String, String, Map)}
     * @param confirmations 客户端提交的候选确认记录
     * @return 存在一条有效且未消费的确认时为 {@code true}
     */
    public boolean verifyAndConsume(Map<String, Object> pending, List<Map<String, Object>> confirmations) {
        return verifyAndConsume(null, pending, confirmations);
    }

    /**
     * 验证候选确认，并以存储层 nonce 消费结果作为最终一次性门槛。
     *
     * <p>任一输入为空、字段不匹配、签名过期或 nonce 已消费都会返回 false；成功后
     * 立即清除该会话的待确认项。</p>
     * @param sessionId 会话标识
     * @param pending 待确认记录
     * @param confirmations 客户端提交的确认候选列表
     * @return 成功验证并首次消费 nonce 时为 {@code true}
     */
    public boolean verifyAndConsume(String sessionId, Map<String, Object> pending,
                                    List<Map<String, Object>> confirmations) {
        if (pending == null || confirmations == null) {
            return false;
        }
        for (Map<String, Object> confirmation : confirmations) {
            if (verify(pending, confirmation)
                    && stateStore.consumeNonce(sessionId, String.valueOf(pending.get("nonce")))) {
                stateStore.clearPending(sessionId);
                return true;
            }
        }
        return false;
    }

    /**
     * 在默认会话分区查找与工具及参数完全匹配的待确认记录。
     * @param tool 工具名
     * @param arguments 原始工具参数
     * @return 匹配的待确认记录，找不到时由存储实现返回 null
     */
    public Map<String, Object> findIssued(String tool, Map<String, Object> arguments) {
        return findIssued(null, tool, arguments);
    }

    /**
     * 按会话、工具和参数查找已签发的待确认记录。
     * @param sessionId 会话标识
     * @param tool 工具名
     * @param arguments 原始工具参数
     * @return 匹配记录；没有匹配项时返回 null
     */
    public Map<String, Object> findIssued(String sessionId, String tool, Map<String, Object> arguments) {
        return stateStore.findPending(sessionId, tool, arguments);
    }

    /**
     * 校验确认记录是否与待确认项匹配且仍在十分钟有效期内。
     *
     * <p>校验包含工具名、参数摘要、nonce、时间戳和常量时间签名比较；字段类型
     * 错误或解析异常均按无效确认处理。</p>
     * @param pending 服务端保存的待确认记录
     * @param confirmation 客户端提交的确认记录
     * @return 所有字段通过校验且签名有效时为 {@code true}
     */
    private boolean verify(Map<String, Object> pending, Map<String, Object> confirmation) {
        try {
            if (confirmation == null
                    || !String.valueOf(pending.get("tool")).equals(String.valueOf(confirmation.get("tool")))) {
                return false;
            }
            Map<String, Object> pendingArguments = mapValue(pending.get("arguments"));
            // 签名已经把服务端保存的原始参数绑定进去；客户端可以省略 arguments，
            // 避免把待确认的敏感值再次回传。旧客户端仍可提交完整参数并接受严格比较。
            Map<String, Object> confirmationArguments = confirmation.containsKey("arguments")
                    ? mapValue(confirmation.get("arguments")) : pendingArguments;
            if (!argsHash(pendingArguments).equals(argsHash(confirmationArguments))) {
                return false;
            }
            String pendingNonce = String.valueOf(pending.get("nonce"));
            if (!pendingNonce.equals(String.valueOf(confirmation.get("nonce")))) {
                return false;
            }
            long timestamp = longValue(confirmation.get("ts"));
            long now = System.currentTimeMillis() / 1000;
            if (timestamp > now || now - timestamp > NONCE_TTL_SECONDS) {
                return false;
            }
            String expected = sign(
                    pendingNonce,
                    String.valueOf(confirmation.get("tool")),
                    argsHash(confirmationArguments),
                    timestamp
            );
            byte[] actualBytes = String.valueOf(confirmation.get("signature"))
                    .getBytes(StandardCharsets.UTF_8);
            byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(actualBytes, expectedBytes);
        } catch (RuntimeException error) {
            return false;
        }
    }

    /**
     * 对参数 JSON 做确定性 SHA-256 摘要，并使用完整十六进制摘要作为绑定值。
     * @param arguments 待规范化的工具参数；null 按空 Map 处理
     * @return 用于确认签名和匹配的短摘要
     * @throws IllegalStateException 参数无法序列化或 SHA-256 不可用时抛出
     */
    private String argsHash(Map<String, Object> arguments) {
        try {
            String json = canonicalMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash confirmation arguments", error);
        }
    }

    /**
     * 使用服务密钥对确认的四元组生成 HMAC-SHA256 十六进制签名。
     * @param nonce 待确认记录的随机 nonce
     * @param tool 工具名
     * @param argsHash 参数摘要
     * @param timestamp 签发时间（Unix 秒）
     * @return HMAC-SHA256 十六进制签名
     * @throws IllegalStateException HMAC 算法不可用时抛出
     */
    private String sign(String nonce, String tool, String argsHash, long timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            byte[] digest = mac.doFinal((nonce + ":" + tool + ":" + argsHash + ":" + timestamp)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to sign confirmation", error);
        }
    }

    /**
     * 生成 16 字节随机 nonce，并编码为无分隔符十六进制字符串。
     * @return 新的 nonce
     */
    private String randomNonce() {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        return HexFormat.of().formatHex(nonce);
    }

    /**
     * 复制工具参数并遮蔽敏感键，生成确认提示中使用的 JSON 文本。
     * @param arguments 待展示的原始参数
     * @return 敏感字段替换为 {@code ***} 的 JSON；序列化失败时返回空对象
     */
    private String redacted(Map<String, Object> arguments) {
        Map<String, Object> safe = redactedMap(arguments);
        try {
            return canonicalMapper.writeValueAsString(safe);
        } catch (JsonProcessingException error) {
            return "{}";
        }
    }

    private Map<String, Object> redactedMap(Map<String, Object> arguments) {
        return redactMap(arguments == null ? Map.of() : arguments);
    }

    private Map<String, Object> redactMap(Map<String, Object> arguments) {
        Map<String, Object> result = new LinkedHashMap<>();
        arguments.forEach((key, value) -> result.put(key,
                isSecretField(key) ? "***" : redactValue(value)));
        return result;
    }

    private Object redactValue(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> nested = new LinkedHashMap<>();
            source.forEach((key, item) -> {
                String name = String.valueOf(key);
                nested.put(name, isSecretField(name) ? "***" : redactValue(item));
            });
            return nested;
        }
        if (value instanceof List<?> source) {
            return source.stream().map(this::redactValue).toList();
        }
        return value;
    }

    private boolean isSecretField(String key) {
        String normalized = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("key") || normalized.contains("token")
                || normalized.contains("secret") || normalized.contains("password")
                || normalized.contains("authorization") || normalized.contains("cookie");
    }

    /**
     * 把未知对象安全转换为字符串键 Map，供确认校验读取字段。
     * @param value 可能是 Map 的对象
     * @return 将键转为字符串后的 Map；非 Map 返回不可变空 Map
     */
    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    /**
     * 将确认记录中的时间戳转换为 long。
     * @param value Number 或可解析为 long 的字符串
     * @return Unix 秒时间戳
     * @throws NumberFormatException value 不是合法数字时抛出
     */
    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
