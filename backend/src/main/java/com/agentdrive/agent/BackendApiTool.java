package com.agentdrive.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 暴露给 Agent 的后端操作工具。
 *
 * <p>工具只允许模型发现并调用 {@link OperationCatalog} 中登记的 operation。
 * 调用结果统一包装为 JSON；参数缺失、未知 action 或未知 operation 会返回结构化
 * 错误，不会让模型直接访问任意 URL、请求头或 Java 入口。</p>
 */
public class BackendApiTool implements AgentTool {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackendApiTool.class);
    private final OperationCatalog catalog;
    private final BackendApiDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    /**
     * 绑定 operation 目录、执行器和 JSON 编解码器。
     *
     * @param catalog 提供 discover、查找和建议的 operation 目录
     * @param dispatcher 执行目录中 operation 的分发器
     * @param objectMapper 将结果编码为工具响应 JSON 的 mapper
     */
    public BackendApiTool(OperationCatalog catalog, BackendApiDispatcher dispatcher, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    /**
     * 返回模型可见的后端工具名。
     *
     * @return 固定为 {@code backend_api}
     */
    @Override
    public String toolName() {
        return "backend_api";
    }

    /**
     * 将 Agent 的原始 JSON 参数转换为后端请求并按当前 owner 执行。
     *
     * @param rawArguments LangChain4j 工具参数 JSON
     * @param context 当前聊天 owner 和 request ID
     * @return 后端 operation 的 JSON 结果
     * @throws JsonProcessingException 参数不是合法 BackendApiRequest 时抛出
     */
    @Override
    public String executeRaw(String rawArguments, AgentToolContext context) throws JsonProcessingException {
        BackendApiRequest request = objectMapper.readValue(rawArguments, BackendApiRequest.class);
        UUID userId = context == null ? null : context.authenticatedUserId();
        String requestId = context == null ? null : context.requestId();
        return execute(request, userId, requestId);
    }

    /**
     * 接收模型的 discover/call 请求并返回结构化 JSON。
     *
     * <p>这是 LangChain4j 暴露的入口；它先将散落参数封装成请求信封，再交由统一
     * 分支处理，因此缺省 action 会执行 discover。</p>
     *
     * @param action {@code discover} 或 {@code call}
     * @param query 用于发现 operation 的自然语言查询
     * @param operation discover 返回的精确 operation 名称
     * @param pathParams operation 允许的路径参数
     * @param queryParams operation 允许的查询参数
     * @param body operation 允许的 JSON 请求体
     * @param files operation 允许的文件元数据
     * @return 可直接交给模型解析的 JSON 字符串
     */
    @Tool(name = "backend_api", value = {"Discover and safely call a registered backend operation. Use discover before call.",
            "Use path_params only for {placeholder} segments in the operation path. Query-string values such as /api/v1/files path, q, mode=semantic, or md5 belong in query_params.",
            "The model cannot provide arbitrary URLs, headers, credentials, or Java entry points."})
    public String execute(
            @P(name = "action", value = "discover or call", required = false) String action,
            @P(name = "query", value = "Natural-language discovery query", required = false) String query,
            @P(name = "operation", value = "Exact operation returned by discover", required = false) String operation,
            @P(name = "path_params", value = "Only placeholders that literally appear inside the operation path, such as sessionId in /sessions/{sessionId}", required = false) Map<String, String> pathParams,
            @P(name = "query_params", value = "Query-string parameters; for GET /api/v1/files, put path here", required = false) Map<String, Object> queryParams,
            @P(name = "body", value = "Validated JSON request body", required = false) Map<String, Object> body,
            @P(name = "files", value = "Validated multipart file metadata", required = false) Map<String, Object> files
    ) {
        BackendApiRequest request = new BackendApiRequest(action, query, operation, pathParams, queryParams, body, files);
        return execute(request, null);
    }

    /**
     * 在没有显式用户上下文时执行工具请求。
     *
     * @param request 已规范化的工具请求
     * @return discover 或 call 的 JSON 响应
     */
    public String execute(BackendApiRequest request) {
        return execute(request, null);
    }

    /**
     * 校验并执行一个工具请求。
     *
     * <p>discover 返回最多六个候选 operation；call 要求 operation 非空、必须登记且
     * 由 dispatcher 执行。未知 operation 会附带名称建议，实际执行结果保留 operation
     * 风险等级供上层确认流程使用。</p>
     *
     * @param request 待处理的工具请求
     * @param userId 当前认证用户，用于 owner-scoped dispatcher；可为空
     * @return 成功或失败的 JSON 响应
     */
    public String execute(BackendApiRequest request, UUID userId) {
        return execute(request, userId, null);
    }

    /**
     * 执行工具请求并把安全的调用摘要写入聊天关联日志。
     *
     * <p>日志只记录参数键名和容器大小，不记录参数值、用户消息、工具输出或认证信息；
     * 原有校验和分发顺序保持不变。</p>
     *
     * @param request 待处理的工具请求
     * @param userId 当前认证用户，用于 owner-scoped dispatcher；可为空
     * @param requestId 当前聊天流的关联 ID；独立调用可为空
     * @return 成功或失败的 JSON 响应
     */
    public String execute(BackendApiRequest request, UUID userId, String requestId) {
        long startedAt = System.nanoTime();
        String correlationId = safeId(requestId);
        if (request != null) {
            LOGGER.info("backend_api_start request_id={} action={} operation={} path={} params={}",
                    correlationId, request.action(), safeId(request.operation()), operationPath(request),
                    parameterSummary(request));
        }
        try {
            String result = executeWithoutLogging(request, userId);
            LOGGER.info("backend_api_end request_id={} action={} operation={} path={} http_status={} duration_ms={}",
                    correlationId, request == null ? "-" : request.action(),
                    request == null ? "-" : safeId(request.operation()), request == null ? "-" : operationPath(request),
                    statusCode(result, request), elapsedMillis(startedAt));
            return result;
        } catch (RuntimeException error) {
            LOGGER.error("backend_api_error request_id={} action={} operation={} path={} duration_ms={}",
                    correlationId, request == null ? "-" : request.action(),
                    request == null ? "-" : safeId(request.operation()), request == null ? "-" : operationPath(request),
                    elapsedMillis(startedAt), ChatLogSupport.safeThrowable(error));
            throw error;
        }
    }

    /**
     * 保留原有 backend_api 校验、allowlist 查找和 dispatcher 调用顺序。
     *
     * @param request 待校验的工具请求
     * @param userId 当前认证 owner
     * @return 工具 JSON 响应
     */
    private String executeWithoutLogging(BackendApiRequest request, UUID userId) {
        if (request == null) {
            return jsonError("invalid_request", "request must not be null", null);
        }
        if (request.isDiscover()) {
            return json(Map.of(
                    "ok", true,
                    "action", "discover",
                    "operations", catalog.discover(request.query())
            ));
        }
        if (!request.isCall()) {
            return jsonError("invalid_action", "action must be discover or call", null);
        }
        if (request.operation() == null || request.operation().isBlank()) {
            return jsonError("missing_operation", "operation is required for call", null);
        }
        OperationDefinition definition = catalog.find(request.operation()).orElse(null);
        if (definition == null) {
            return jsonError("unknown_operation", "operation is not registered", catalog.suggestions(request.operation()));
        }
        String parameterError = validatePathParameters(definition, request);
        if (parameterError != null) {
            return parameterError;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("action", "call");
        result.put("operation", definition.operation());
        result.put("risk", definition.risk());
        result.put("result", dispatcher.dispatch(definition, request, userId));
        return json(result);
    }

    /**
     * 校验模型是否把 URL 路径参数放入了正确的参数容器。
     *
     * <p>不接受静默忽略：路径模板没有占位符时，任何 {@code path_params} 都是错误，
     * 例如 {@code GET /api/v1/files} 的 {@code path} 是 query 参数；模板有占位符时，
     * 则要求键集合与占位符完全一致，避免把缺失参数误发给业务 dispatcher。</p>
     *
     * @param definition 已登记的 operation 定义
     * @param request 模型提交的工具请求
     * @return 参数位置错误的 JSON；参数位置正确时返回 {@code null}
     */
    private String validatePathParameters(OperationDefinition definition, BackendApiRequest request) {
        List<String> expectedList = definition.pathParameterNames();
        Set<String> expected = new LinkedHashSet<>(expectedList);
        Set<String> received = new LinkedHashSet<>(request.pathParams().keySet());
        if (expected.equals(received)) {
            return null;
        }
        String hint = expected.isEmpty()
                ? "This operation has no path placeholders; put query-string values such as path in query_params."
                : "path_params must contain exactly the placeholders from the operation path; put query-string values in query_params and JSON values in body.";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("expected_path_params", expectedList);
        details.put("received_path_params", List.copyOf(received));
        details.put("hint", hint);
        return jsonError("invalid_parameter_location", hint, details);
    }

    /**
     * 生成不含参数值的工具请求摘要。
     *
     * @param request 已规范化的工具请求
     * @return 各参数容器的键名和数量摘要
     */
    private String parameterSummary(BackendApiRequest request) {
        return "path_keys=" + keys(request.pathParams())
                + ",query_keys=" + keys(request.queryParams())
                + ",body_keys=" + keys(request.body())
                + ",files=" + request.files().size();
    }

    /**
     * 按字典序列出参数键名，避免日志包含参数值。
     *
     * @param values 参数映射
     * @return 稳定的键名列表
     */
    private String keys(Map<?, ?> values) {
        return values.keySet().stream().map(String::valueOf).sorted().collect(Collectors.joining(",", "[", "]"));
    }

    /**
     * 从 operation 定义读取可检索的路径；内部 operation 没有 HTTP 路径。
     *
     * @param request 工具请求
     * @return HTTP 路径或短横线
     */
    private String operationPath(BackendApiRequest request) {
        if (request == null || request.operation() == null) {
            return "-";
        }
        OperationDefinition definition = catalog.find(request.operation()).orElse(null);
        return definition == null || definition.path().isBlank() ? "-" : safeId(definition.path());
    }

    /**
     * 从工具 JSON 结果推导日志用 HTTP 状态；内部 operation 用 0 表示不适用。
     *
     * @param result 工具 JSON 结果
     * @param request 原始工具请求
     * @return 显式状态、成功 200、业务错误 500 或内部操作 0
     */
    private int statusCode(String result, BackendApiRequest request) {
        if (request != null && request.operation() != null && request.operation().startsWith("INTERNAL ")) {
            return 0;
        }
        try {
            JsonNode root = objectMapper.readTree(result);
            JsonNode status = root.path("status");
            if (status.isIntegralNumber()) {
                return status.asInt();
            }
            return root.path("ok").asBoolean(true) ? 200 : 500;
        } catch (JsonProcessingException ignored) {
            return 500;
        }
    }

    /**
     * 限制日志标识的字符集和长度，避免日志注入。
     *
     * @param value 请求或 operation 标识
     * @return 可安全记录的短标识
     */
    private String safeId(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String safe = value.replaceAll("[^A-Za-z0-9._:/-]", "_");
        return safe.substring(0, Math.min(safe.length(), 128));
    }

    /**
     * 计算工具调用耗时。
     *
     * @param startedAt 单调时钟起点
     * @return 非负耗时毫秒
     */
    private long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    /**
     * 从请求中解析已登记的 operation 定义，但不执行它。
     *
     * @param request 可能包含 operation 名称的请求；为空或缺名时返回 null
     * @return 匹配的定义，找不到时返回 {@code null}
     */
    public OperationDefinition definitionFor(BackendApiRequest request) {
        if (request == null || request.operation() == null) {
            return null;
        }
        return catalog.find(request.operation()).orElse(null);
    }

    /**
     * 从通用 Agent 工具参数中解析后端 operation 定义。
     *
     * @param arguments 已解析的 backend_api 参数
     * @param context 当前聊天上下文；后端 operation 定义不依赖它
     * @return 已登记 operation；参数格式错误或 operation 未登记时返回 null
     */
    @Override
    public OperationDefinition definitionFor(Map<String, Object> arguments, AgentToolContext context) {
        try {
            return definitionFor(objectMapper.convertValue(arguments, BackendApiRequest.class));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    /**
     * 生成统一的工具错误响应。
     *
     * @param code 机器可读的错误代码
     * @param message 面向模型的错误说明
     * @param suggestions 可选的 operation 候选列表
     * @return 包含 {@code ok:false}、错误代码和说明的 JSON
     */
    private String jsonError(String code, String message, Object suggestions) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("error", code);
        result.put("message", message);
        if (suggestions != null) {
            result.put("suggestions", suggestions);
        }
        return json(result);
    }

    /**
     * 使用注入的 Jackson mapper 编码工具响应。
     *
     * @param value 待编码的响应对象
     * @return JSON 文本
     * @throws IllegalStateException 当响应无法被 mapper 序列化时抛出
     */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to encode backend_api result", e);
        }
    }
}
