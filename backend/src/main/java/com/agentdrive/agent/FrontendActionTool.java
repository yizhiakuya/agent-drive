package com.agentdrive.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 暴露给 Agent 的动态前端语义动作工具。
 *
 * <p>工具协议固定为 discover/call，但动作目录由当前浏览器随聊天请求提供。模型看到
 * 的是动作名、用途和参数 schema，而不是 JavaScript 函数名；浏览器收到动作后仍需从
 * 本地注册表取出 handler 执行。这样新增前端交互只需更新前端注册表，不需要新增后端
 * operation，同时不会把任意代码、URL、请求头或 {@code eval} 暴露给模型。</p>
 */
public final class FrontendActionTool implements AgentTool {
    /** 单次发现最多返回的前端动作数量。 */
    private static final int DISCOVERY_LIMIT = 12;
    /** 单次请求允许向模型公开的最大能力数量。 */
    private static final int CAPABILITY_LIMIT = 64;
    /** 动作参数 JSON 的大小上限，避免模型构造异常大的浏览器事件。 */
    private static final int ARGUMENT_LIMIT = 16 * 1024;
    /** 用于编码工具输出的 JSON 映射器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建前端动作工具。
     *
     * @param objectMapper 用于编码发现结果和动作请求的 JSON 映射器
     * @throws NullPointerException 映射器为空时抛出
     */
    public FrontendActionTool(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 返回模型可见的前端工具名。
     *
     * @return 固定为 {@code frontend_api}
     */
    @Override
    public String toolName() {
        return "frontend_api";
    }

    /**
     * 将 Agent 的原始 JSON 参数转换为前端动作请求。
     *
     * @param rawArguments LangChain4j 工具参数 JSON
     * @param context 当前聊天的浏览器能力清单
     * @return 动作目录或动作请求 JSON
     * @throws JsonProcessingException 参数不是合法 FrontendActionRequest 时抛出
     */
    @Override
    public String executeRaw(String rawArguments, AgentToolContext context) throws JsonProcessingException {
        FrontendActionRequest request = objectMapper.readValue(rawArguments, FrontendActionRequest.class);
        List<Map<String, Object>> capabilities = context == null ? List.of() : context.frontendCapabilities();
        return execute(request, capabilities);
    }

    /**
     * 接收模型的 discover/call 请求并返回结构化 JSON。
     *
     * @param action {@code discover} 或 {@code call}；为空时默认为 discover
     * @param query 用于发现前端动作的自然语言查询
     * @param operation discover 返回的精确动作名
     * @param arguments 动作参数；字段由前端能力 schema 决定
     * @return 可交给模型解析的动作目录或动作请求 JSON
     */
    @Tool(name = "frontend_api", value = {
            "Discover and request a registered browser UI action. Use discover before call.",
            "The action catalog comes from the current browser. This tool cannot execute arbitrary JavaScript, function names, eval, URLs, headers, or credentials.",
            "Call only an exact operation returned by discover and put its values in the arguments object."
    })
    public String execute(
            @P(name = "action", value = "discover or call", required = false) String action,
            @P(name = "query", value = "Natural-language action discovery query", required = false) String query,
            @P(name = "operation", value = "Exact action returned by discover", required = false) String operation,
            @P(name = "arguments", value = "Validated action arguments described by the capability schema", required = false)
            Map<String, Object> arguments
    ) {
        return execute(new FrontendActionRequest(action, query, operation, arguments), List.of());
    }

    /**
     * 执行当前聊天请求携带的前端能力目录。
     *
     * @param request 模型提交的动作信封
     * @param rawCapabilities 当前浏览器注册的能力清单；只作为动作 allowlist 使用
     * @return discover 或 call 的 JSON 响应
     */
    public String execute(FrontendActionRequest request, List<Map<String, Object>> rawCapabilities) {
        if (request == null) {
            return jsonError("invalid_request", "request must not be null", null);
        }
        List<ActionDefinition> capabilities = normalizeCapabilities(rawCapabilities);
        String action = request.action() == null || request.action().isBlank()
                ? "discover"
                : request.action().trim().toLowerCase(Locale.ROOT);
        if ("discover".equals(action)) {
            return json(Map.of(
                    "ok", true,
                    "action", "discover",
                    "frontend_actions", discover(request.query(), capabilities)
            ));
        }
        if (!"call".equals(action)) {
            return jsonError("invalid_action", "action must be discover or call", null);
        }
        if (request.operation() == null || request.operation().isBlank()) {
            return jsonError("missing_operation", "operation is required for call", null);
        }
        ActionDefinition definition = capabilities.stream()
                .filter(candidate -> candidate.operation().equals(request.operation()))
                .findFirst()
                .orElse(null);
        if (definition == null) {
            return jsonError("unknown_operation", "frontend action is not registered by the current browser",
                    suggestions(request.operation(), capabilities));
        }
        String parameterError = validateArguments(definition, request.arguments());
        if (parameterError != null) {
            return parameterError;
        }
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        Map<String, Object> frontendAction = new LinkedHashMap<>();
        frontendAction.put("operation", definition.operation());
        frontendAction.put("arguments", new LinkedHashMap<>(arguments));
        frontendAction.put("target_tab", definition.targetTab());
        frontendAction.put("summary", definition.summary());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("action", "call");
        result.put("operation", definition.operation());
        result.put("risk", "green");
        result.put("frontend_action", frontendAction);
        return json(result);
    }

    /**
     * 将当前能力目录中的动作映射为绿色内部 operation，供 runtime 记录和重放。
     *
     * @param request 模型提交的动作请求
     * @param rawCapabilities 当前浏览器能力清单
     * @return 可记录的 operation；discover、未知动作或非 call 请求返回空
     */
    public OperationDefinition definitionFor(FrontendActionRequest request,
                                             List<Map<String, Object>> rawCapabilities) {
        if (request == null || request.operation() == null || !"call".equalsIgnoreCase(request.action())) {
            return null;
        }
        ActionDefinition definition = normalizeCapabilities(rawCapabilities).stream()
                .filter(candidate -> candidate.operation().equals(request.operation()))
                .findFirst()
                .orElse(null);
        return definition == null
                ? null
                : OperationDefinition.internal("frontend." + definition.operation(), definition.summary(), "green");
    }

    /**
     * 从通用 Agent 工具参数中解析当前浏览器动作定义。
     *
     * @param arguments 已解析的 frontend_api 参数
     * @param context 当前聊天上下文，提供浏览器能力清单
     * @return 当前清单中的绿色前端 operation；未命中时返回 null
     */
    @Override
    public OperationDefinition definitionFor(Map<String, Object> arguments, AgentToolContext context) {
        try {
            FrontendActionRequest request = objectMapper.convertValue(arguments, FrontendActionRequest.class);
            List<Map<String, Object>> capabilities = context == null ? List.of() : context.frontendCapabilities();
            return definitionFor(request, capabilities);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    /**
     * 从成功的 frontend_api 结果中提取浏览器动作 SSE 数据。
     *
     * @param parsedResult 完整工具输出解析后的对象
     * @return frontend_action 对象；工具错误或结果没有动作时返回 null
     */
    @Override
    public Map<String, Object> clientEvent(Map<String, Object> parsedResult) {
        if (parsedResult == null || !(parsedResult.get("frontend_action") instanceof Map<?, ?> rawAction)) {
            return null;
        }
        try {
            return objectMapper.convertValue(rawAction, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    /**
     * 将浏览器能力清单转换为服务端可安全使用的有限目录。
     *
     * <p>清单中的 summary、target_tab 和 schema 都会被截断/筛选；客户端传入的清单
     * 只能扩大前端动作的可发现范围，不能获得后端 API 分发权限。</p>
     *
     * @param rawCapabilities 原始能力对象列表
     * @return 去重、限量并规范化后的动作定义
     */
    private List<ActionDefinition> normalizeCapabilities(List<Map<String, Object>> rawCapabilities) {
        if (rawCapabilities == null || rawCapabilities.isEmpty()) {
            return List.of();
        }
        Map<String, ActionDefinition> unique = new LinkedHashMap<>();
        for (Map<String, Object> raw : rawCapabilities) {
            if (raw == null || unique.size() >= CAPABILITY_LIMIT) {
                break;
            }
            String operation = text(raw.get("operation"));
            String summary = text(raw.get("summary"));
            String targetTab = text(raw.get("target_tab"));
            if (!operation.matches("[A-Za-z][A-Za-z0-9_.:-]{0,99}")
                    || operation.equals("frontend_api")
                    || summary.isBlank()
                    || summary.length() > 500
                    || !targetTab.matches("[A-Za-z0-9_-]{1,32}")) {
                continue;
            }
            Map<String, Object> schema = normalizeSchema(raw.get("parameters"));
            Set<String> required = requiredArguments(schema);
            Set<String> allowed = allowedArguments(schema);
            Set<String> stringArguments = stringArguments(schema);
            unique.putIfAbsent(operation, new ActionDefinition(
                    operation, summary, targetTab, schema, required, allowed, stringArguments));
        }
        return List.copyOf(unique.values());
    }

    /**
     * 按自然语言查询筛选当前浏览器的动作目录。
     *
     * @param query 用户或模型的发现关键词
     * @param capabilities 已规范化的动作目录
     * @return 最多十二个动作描述
     */
    private Collection<Map<String, Object>> discover(String query, List<ActionDefinition> capabilities) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return capabilities.stream()
                .filter(definition -> normalized.isBlank() || matches(normalized, definition))
                .limit(DISCOVERY_LIMIT)
                .map(this::describe)
                .toList();
    }

    /**
     * 为未知动作名生成有限建议。
     *
     * @param operation 模型提交的动作名片段
     * @param capabilities 已规范化的动作目录
     * @return 最多六个动作名
     */
    private List<String> suggestions(String operation, List<ActionDefinition> capabilities) {
        String normalized = operation.toLowerCase(Locale.ROOT);
        return capabilities.stream()
                .filter(definition -> definition.operation().toLowerCase(Locale.ROOT).contains(normalized)
                        || definition.summary().toLowerCase(Locale.ROOT).contains(normalized))
                .limit(6)
                .map(ActionDefinition::operation)
                .toList();
    }

    /**
     * 校验动作参数键、schema 基本类型和事件大小。
     *
     * @param definition 已登记动作
     * @param rawArguments 模型提交的参数
     * @return 结构化错误 JSON；参数有效时返回空
     */
    private String validateArguments(ActionDefinition definition, Map<String, Object> rawArguments) {
        Map<String, Object> arguments = rawArguments == null ? Map.of() : rawArguments;
        if (arguments.size() > 32) {
            return jsonError("invalid_arguments", "arguments contains too many fields", null);
        }
        try {
            if (objectMapper.writeValueAsBytes(arguments).length > ARGUMENT_LIMIT) {
                return jsonError("invalid_arguments", "arguments is too large", null);
            }
        } catch (JsonProcessingException error) {
            return jsonError("invalid_arguments", "arguments must be JSON-compatible", null);
        }
        if (!arguments.keySet().stream().allMatch(definition.allowedArguments()::contains)
                || !arguments.keySet().containsAll(definition.requiredArguments())) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("required", definition.requiredArguments());
            details.put("allowed", definition.allowedArguments());
            details.put("received", new ArrayList<>(arguments.keySet()));
            return jsonError("invalid_arguments", "arguments do not match the registered capability schema", details);
        }
        for (String name : definition.stringArguments()) {
            if (arguments.containsKey(name) && !(arguments.get(name) instanceof String)) {
                return jsonError("invalid_arguments", "argument " + name + " must be a string", null);
            }
        }
        Object path = arguments.get("path");
        if (path != null && definition.operation().startsWith("files.")
                && (!(path instanceof String) || !isSafeRelativePath((String) path,
                "files.open_folder".equals(definition.operation())))) {
            return jsonError("invalid_path",
                    "files action path must be an owner-relative POSIX path without '.', '..', '\\\\', or a leading '/'"
                            + ("files.open_folder".equals(definition.operation())
                            ? "; empty path is allowed for the root folder" : ""),
                    null);
        }
        return null;
    }

    /**
     * 判断路径是否保持在当前用户的相对文件空间内。
     *
     * @param path 待校验路径
     * @param allowRoot 是否允许空路径代表根目录
     * @return 路径安全且符合 POSIX 形式时为 true
     */
    private boolean isSafeRelativePath(String path, boolean allowRoot) {
        if (path == null || path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0
                || path.startsWith("/") || (!allowRoot && path.isBlank())) {
            return false;
        }
        if (path.isBlank()) {
            return allowRoot;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 生成 discover 展示用的动作对象。
     *
     * @param definition 动作定义
     * @return 只包含模型需要的动作元数据
     */
    private Map<String, Object> describe(ActionDefinition definition) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", definition.operation());
        result.put("summary", definition.summary());
        result.put("target_tab", definition.targetTab());
        result.put("parameters", definition.schema());
        return result;
    }

    /**
     * 判断查询是否命中动作的 operation、用途或目标页。
     *
     * @param query 已规范化查询
     * @param definition 动作定义
     * @return 查询命中时为 true
     */
    private boolean matches(String query, ActionDefinition definition) {
        String haystack = (definition.operation() + " " + definition.summary() + " " + definition.targetTab())
                .toLowerCase(Locale.ROOT);
        for (String token : query.split("\\s+")) {
            if (!token.isBlank() && haystack.contains(token)) {
                return true;
            }
        }
        if (query.contains("目录") || query.contains("文件夹")) {
            return definition.operation().contains("folder");
        }
        if (query.contains("打开") || query.contains("查看") || query.contains("预览") || query.contains("详情")) {
            return definition.operation().contains("open") || definition.operation().contains("details");
        }
        return query.contains("文件") && definition.operation().startsWith("files.");
    }

    /**
     * 读取并截断能力字段中的字符串值。
     *
     * @param value 原始字段
     * @return 非空字符串或空字符串
     */
    private String text(Object value) {
        return value instanceof String string ? string.trim() : "";
    }

    /**
     * 只保留前端动作需要的简单参数 schema。
     *
     * @param rawSchema 浏览器提供的 schema
     * @return 可安全回显和校验的 schema
     */
    private Map<String, Object> normalizeSchema(Object rawSchema) {
        if (!(rawSchema instanceof Map<?, ?> source)) {
            return Map.of("type", "object", "properties", Map.of(), "required", List.of());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", normalizeStringList(source.get("required")));
        Map<String, Object> properties = new LinkedHashMap<>();
        if (source.get("properties") instanceof Map<?, ?> rawProperties) {
            for (Map.Entry<?, ?> entry : rawProperties.entrySet()) {
                if (properties.size() >= 32 || !(entry.getKey() instanceof String name)) {
                    continue;
                }
                if (entry.getValue() instanceof Map<?, ?> rawProperty) {
                    Map<String, Object> property = new LinkedHashMap<>();
                    String type = text(rawProperty.get("type"));
                    if (Set.of("string", "number", "integer", "boolean", "array", "object").contains(type)) {
                        property.put("type", type);
                    }
                    String description = text(rawProperty.get("description"));
                    if (!description.isBlank() && description.length() <= 300) {
                        property.put("description", description);
                    }
                    properties.put(name, property);
                }
            }
        }
        schema.put("properties", properties);
        return Map.copyOf(schema);
    }

    /**
     * 从 schema 读取 required 字段。
     *
     * @param schema 已规范化 schema
     * @return 去重后的字段集合
     */
    private Set<String> requiredArguments(Map<String, Object> schema) {
        return new LinkedHashSet<>(normalizeStringList(schema.get("required")));
    }

    /**
     * 从 schema 读取允许字段；没有 properties 时默认为空集合。
     *
     * @param schema 已规范化 schema
     * @return 允许的字段集合
     */
    private Set<String> allowedArguments(Map<String, Object> schema) {
        if (!(schema.get("properties") instanceof Map<?, ?> properties)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object key : properties.keySet()) {
            if (key instanceof String name) {
                result.add(name);
            }
        }
        return result;
    }

    /**
     * 从 schema 读取必须为字符串的字段。
     *
     * @param schema 已规范化 schema
     * @return 字符串字段集合
     */
    private Set<String> stringArguments(Map<String, Object> schema) {
        if (!(schema.get("properties") instanceof Map<?, ?> properties)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            if (entry.getKey() instanceof String name && entry.getValue() instanceof Map<?, ?> property
                    && "string".equals(property.get("type"))) {
                result.add(name);
            }
        }
        return result;
    }

    /**
     * 读取 schema 中的字符串列表。
     *
     * @param value 原始列表
     * @return 最多三十二个非空字符串
     */
    private List<String> normalizeStringList(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : collection) {
            if (result.size() >= 32) {
                break;
            }
            String name = text(item);
            if (!name.isBlank() && name.matches("[A-Za-z][A-Za-z0-9_.:-]{0,99}")) {
                result.add(name);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 编码工具响应。
     *
     * @param value 待编码对象
     * @return JSON 文本；编码异常时返回稳定错误
     */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            return "{\"ok\":false,\"error\":\"response_encoding_failed\"}";
        }
    }

    /**
     * 编码结构化错误。
     *
     * @param code 稳定错误码
     * @param message 面向模型的错误说明
     * @param details 可选细节
     * @return JSON 错误文本
     */
    private String jsonError(String code, String message, Object details) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("status", 400);
        result.put("code", code);
        result.put("error", code);
        result.put("message", message);
        result.put("detail", message);
        if (details != null) {
            result.put("details", details);
        }
        return json(result);
    }

    /**
     * 模型可见的前端工具请求信封。
     *
     * @param action discover 或 call
     * @param query 发现查询
     * @param operation 精确动作名
     * @param arguments 动作参数
     */
    public record FrontendActionRequest(
            String action,
            String query,
            String operation,
            Map<String, Object> arguments
    ) {
    }

    /**
     * 规范化后的前端能力目录项。
     *
     * @param operation 稳定动作名
     * @param summary 动作用途说明
     * @param targetTab 目标前端页签
     * @param schema 可展示给模型的参数 schema
     * @param requiredArguments 必填字段
     * @param allowedArguments 允许字段
     * @param stringArguments 必须为字符串的字段
     */
    private record ActionDefinition(
            String operation,
            String summary,
            String targetTab,
            Map<String, Object> schema,
            Set<String> requiredArguments,
            Set<String> allowedArguments,
            Set<String> stringArguments
    ) {
    }
}
