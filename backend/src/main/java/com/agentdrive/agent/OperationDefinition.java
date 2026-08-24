package com.agentdrive.agent;

import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 可调用 operation 的不可变定义。
 *
 * <p>HTTP operation 必须位于 {@code /api/v1/} 且避开 auth、chat 和 health；内部
 * operation 使用 {@code INTERNAL} 方法。risk 为空时由方法和路径推导，供确认策略
 * 决定是否需要用户签名确认。</p>
 */
public record OperationDefinition(
        String operation,
        String method,
        String path,
        String summary,
        String risk,
        ReplayPolicy replayPolicy
) {
    private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([^{}]+)}");

    /**
     * 规范化字段并验证 operation 是否处于 Agent API allowlist。
     * @param operation 唯一 operation 名
     * @param method HTTP 方法或 {@code INTERNAL}
 * @param path HTTP 路径；内部 operation 为空
 * @param summary 面向 discover 结果的用途说明
 * @param risk 显式风险级别；为空时按方法和路径推导
 * @param replayPolicy 显式重放策略；为空时按 operation 类型保守推导
     * @throws NullPointerException operation 为空时抛出
     * @throws IllegalArgumentException 协议、路径或内部 operation 规则不满足时抛出
     */
    public OperationDefinition {
        operation = requireText(operation, "operation");
        method = method == null ? "" : method.toUpperCase(Locale.ROOT);
        path = path == null ? "" : path;
        summary = summary == null ? "" : summary;
        if (operation.startsWith("INTERNAL ")) {
            if (!method.equals("INTERNAL")) {
                throw new IllegalArgumentException("Internal operations must use method INTERNAL");
            }
        } else {
            if (!method.matches("GET|POST|PUT|PATCH|DELETE|OPTIONS|HEAD")) {
                throw new IllegalArgumentException("Unsupported HTTP method: " + method);
            }
            if (!path.startsWith("/api/v1/")) {
                throw new IllegalArgumentException("HTTP operation path must stay under /api/v1");
            }
            if (path.startsWith("/api/v1/auth")
                    || path.startsWith("/api/v1/chat")
                    || path.equals("/api/v1/health")) {
                throw new IllegalArgumentException("Operation is outside the Agent API allowlist");
            }
        }
        risk = risk == null || risk.isBlank() ? OperationCatalog.riskFor(method, path) : risk;
        replayPolicy = replayPolicy == null
                ? ReplayPolicy.defaultFor(method, path)
                : replayPolicy;
    }

    /**
     * 兼容旧调用方的五字段构造器。重放策略由方法和路径保守推导。
     * @param operation 稳定 operation 名称
     * @param method HTTP 方法或 INTERNAL
     * @param path HTTP 路径
     * @param summary operation 说明
     * @param risk 风险级别；为空时推导
     */
    public OperationDefinition(String operation, String method, String path,
                               String summary, String risk) {
        this(operation, method, path, summary, risk, null);
    }

    /**
     * 创建一个受 allowlist 约束的 HTTP operation。
     * @param method 合法 HTTP 方法
     * @param path {@code /api/v1/} 下的 API 路径
     * @param summary discover 展示的操作说明
     * @return operation 名为 {@code METHOD + " " + path} 的定义
     */
    public static OperationDefinition http(String method, String path, String summary) {
        return new OperationDefinition(method.toUpperCase(Locale.ROOT) + " " + path,
                method, path, summary, null, null);
    }

    /**
     * 创建带显式风险和重放策略的 HTTP operation。
     * @param method HTTP 方法
     * @param path Agent allowlist 下的路径
     * @param summary operation 说明
     * @param risk 风险级别
     * @param replayPolicy 是否允许在同一 session 重放
     * @return operation 定义
     */
    public static OperationDefinition http(String method, String path, String summary,
                                           String risk, ReplayPolicy replayPolicy) {
        return new OperationDefinition(method.toUpperCase(Locale.ROOT) + " " + path,
                method, path, summary, risk, replayPolicy);
    }

    /**
     * 创建不经 HTTP 的内部 operation 定义。
     * @param name 内部服务操作名
     * @param summary discover 展示的操作说明
     * @param risk 风险级别；通常应为 red
     * @return 使用 {@code INTERNAL name} 标识的定义
     */
    public static OperationDefinition internal(String name, String summary, String risk) {
        return new OperationDefinition("INTERNAL " + name, "INTERNAL", "", summary, risk,
                ReplayPolicy.NONE);
    }

    /**
     * 创建带显式重放策略的内部 operation。
     * @param name 内部 operation 名
     * @param summary operation 说明
     * @param risk 风险级别
     * @param replayPolicy 重放策略
     * @return operation 定义
     */
    public static OperationDefinition internal(String name, String summary, String risk,
                                               ReplayPolicy replayPolicy) {
        return new OperationDefinition("INTERNAL " + name, "INTERNAL", "", summary, risk,
                replayPolicy);
    }

    /**
     * 提取 HTTP 路径模板中的占位参数名。
     *
     * <p>返回值用于校验 {@code backend_api.path_params} 的容器边界；查询字符串和
     * JSON body 字段不属于这里，因此不会被误当成 URL 路径参数。</p>
     *
     * @return 按路径出现顺序排列且不重复的占位参数名；内部 operation 返回空列表
     */
    public List<String> pathParameterNames() {
        if (path.isBlank()) {
            return List.of();
        }
        Matcher matcher = PATH_PARAMETER.matcher(path);
        Set<String> names = new LinkedHashSet<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return List.copyOf(names);
    }

    /**
     * 要求一个字段非空且非空白。
     * @param value 字段值
     * @param field 用于异常消息的字段名
     * @return 原始 value
     * @throws NullPointerException value 为 null 时抛出
     * @throws IllegalArgumentException value 为空白时抛出
     */
    private static String requireText(String value, String field) {
        return Objects.requireNonNull(value, field + " must not be null").isBlank()
                ? throwBlank(field)
                : value;
    }

    /**
     * 以字段名构造“不能为空”的参数异常。
     * @param field 出错字段名
     * @return 此方法永不正常返回
     * @throws IllegalArgumentException 始终抛出
     */
    private static String throwBlank(String field) {
        throw new IllegalArgumentException(field + " must not be blank");
    }
}
