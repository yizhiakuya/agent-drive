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
     * @param discoveryOffset discover 结果起始偏移
     * @param discoveryLimit discover 单页数量，最大 20
     * @param operation discover 返回的精确 operation 名称
     * @param pathParams operation 允许的路径参数
     * @param queryParams operation 允许的查询参数
     * @param body operation 允许的 JSON 请求体
     * @param files operation 允许的文件元数据
     * @return 可直接交给模型解析的 JSON 字符串
     */
    @Tool(name = "backend_api", value = {"Discover and safely call a registered backend operation. Use discover before call. Discovery is paginated; continue from next_offset while has_more is true.",
            "Use path_params only for {placeholder} segments in the operation path. Query-string values such as /api/v1/files path, q, mode=semantic, or md5 belong in query_params.",
            "The model cannot provide arbitrary URLs, headers, credentials, or Java entry points."})
    public String execute(
            @P(name = "action", value = "discover or call", required = false) String action,
            @P(name = "query", value = "Natural-language discovery query", required = false) String query,
            @P(name = "discovery_offset", value = "Discover result offset; use next_offset from the previous page", required = false) Integer discoveryOffset,
            @P(name = "discovery_limit", value = "Discover page size from 1 to 20; defaults to 6", required = false) Integer discoveryLimit,
            @P(name = "operation", value = "Exact operation returned by discover", required = false) String operation,
            @P(name = "path_params", value = "Only placeholders that literally appear inside the operation path, such as sessionId in /sessions/{sessionId}", required = false) Map<String, String> pathParams,
            @P(name = "query_params", value = "Query-string parameters; for GET /api/v1/files, put path here", required = false) Map<String, Object> queryParams,
            @P(name = "body", value = "Validated JSON request body", required = false) Map<String, Object> body,
            @P(name = "files", value = "Validated multipart file metadata", required = false) Map<String, Object> files
    ) {
        BackendApiRequest request = new BackendApiRequest(
                action, query, discoveryOffset, discoveryLimit, operation, pathParams, queryParams, body, files);
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
     * <p>discover 返回带总数和继续偏移的候选页；call 要求 operation 非空、必须登记且
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
     * 执行 {@code backend_api} 的核心分支逻辑。
     *
     * <p>这里不接收任意 URL，也不直接拼接 HTTP 请求，而是先根据 {@code action}
     * 在“能力发现”和“精确 operation 调用”之间分流。调用分支必须命中
     * {@link OperationCatalog} 的登记项，再交给 owner-aware dispatcher；因此模型只能
     * 使用已登记、已校验并绑定业务 Handler 的能力。</p>
     *
     * @param request 已由工具入口规范化的请求信封
     * @param userId 当前认证 owner；由服务端注入，不能由模型提交
     * @return 包含成功结果或结构化业务错误的 JSON
     */
    private String executeWithoutLogging(BackendApiRequest request, UUID userId) {
        // 防止直接调用入口传入 null；协议错误以工具 JSON 返回，不抛出到模型循环外。
        if (request == null) {
            return jsonError("invalid_request", "request must not be null", null);
        }

        // discover 只查询当前 allowlist，不执行任何文件、配置或索引业务。
        if (request.isDiscover()) {
            OperationCatalog.DiscoveryPage page = catalog.discover(
                    request.query(), request.discoveryOffset(), request.discoveryLimit());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("action", "discover");
            // operation 描述中同时带风险等级和参数 Schema，帮助模型下一轮正确组装 call。
            result.put("operations", page.operations().stream()
                    .map(this::operationDescription)
                    .toList());
            // 分页元数据必须原样返回，模型才能继续读取完整的匹配目录。
            result.put("total_matches", page.totalMatches());
            result.put("returned", page.operations().size());
            result.put("offset", page.offset());
            result.put("limit", page.limit());
            result.put("has_more", page.hasMore());
            result.put("next_offset", page.nextOffset());
            return json(result);
        }

        // 除 discover 外只接受 call；其他 action 不进入 dispatcher。
        if (!request.isCall()) {
            return jsonError("invalid_action", "action must be discover or call", null);
        }

        // call 必须携带 discover 返回的精确 operation 名称。
        if (request.operation() == null || request.operation().isBlank()) {
            return jsonError("missing_operation", "operation is required for call", null);
        }

        // 只允许登记目录中的 operation，未知名称不会被当作 URL 或 Java 方法执行。
        OperationDefinition definition = catalog.find(request.operation()).orElse(null);
        if (definition == null) {
            return jsonError("unknown_operation", "operation is not registered", catalog.suggestions(request.operation()));
        }

        // 路径模板参数必须放在 path_params；query_params/body 的业务字段由具体 Handler 再校验。
        String parameterError = validatePathParameters(definition, request);
        if (parameterError != null) {
            return parameterError;
        }

        // 先建立统一 envelope，保留 operation 和 risk，便于 runtime、审计和前端展示。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("action", "call");
        result.put("operation", definition.operation());
        result.put("risk", definition.risk());
        try {
            // dispatcher 使用服务端注入的 userId 路由到 owner-scoped Handler；模型不能越过这层。
            Map<String, Object> dispatchResult = dispatcher.dispatch(definition, request, userId);
            result.put("result", dispatchResult);

            // Handler 返回的失败也要提升到外层，否则模型可能把嵌套失败误判为成功。
            if (dispatchResult == null) {
                markDispatchFailure(result, null, 500, "operation_failed", "backend operation returned no result");
            } else if (Boolean.FALSE.equals(dispatchResult.get("ok"))) {
                markDispatchFailure(result, dispatchResult, dispatchStatus(dispatchResult), null, null);
            }
            return json(result);
        } catch (RuntimeException error) {
            // 业务失败转换成结构化工具结果，而不是让工具协议异常打断整个模型循环。
            // 这样模型可以解释真实原因，而不是把一次接口失败误报成 Provider 中断。
            int status = operationStatus(error);
            String code = status == 400 ? "invalid_business_request" :
                    status == 503 ? "provider_unavailable" : "operation_failed";
            result.put("ok", false);
            result.put("status", status);
            result.put("code", code);
            result.put("error", code);
            result.put("message", ChatLogSupport.message(error));
            result.put("detail", ChatLogSupport.message(error));
            return json(result);
        }
    }

    /**
     * 将 owner handler 返回的业务失败提升到 backend_api envelope 顶层。
     *
     * <p>dispatcher 的结果仍完整保留在 {@code result} 中供模型和历史回放使用；顶层
     * {@code ok=false} 则让 SSE、前端工具步骤和日志状态机不会把嵌套失败显示为成功。</p>
     *
     * @param envelope backend_api 外层响应
     * @param failure dispatcher 返回的失败结果，可为空
     * @param status 推导出的 HTTP 风格状态
     * @param fallbackCode dispatcher 没有稳定错误码时使用的代码
     * @param fallbackDetail dispatcher 没有错误说明时使用的详情
     */
    private void markDispatchFailure(Map<String, Object> envelope,
                                     Map<String, Object> failure,
                                     int status,
                                     String fallbackCode,
                                     String fallbackDetail) {
        envelope.put("ok", false);
        envelope.put("status", status);
        String rawCode = failure == null ? null : textValue(failure.get("code"));
        String rawError = failure == null ? null : textValue(failure.get("error"));
        String code = stableCode(rawCode) ? rawCode : stableCode(rawError) ? rawError
                : fallbackCode == null ? "operation_failed" : fallbackCode;
        String detail = failure == null ? fallbackDetail
                : firstText(failure.get("detail"), failure.get("message"), failure.get("error"));
        if (detail == null) detail = fallbackDetail == null ? code : fallbackDetail;
        envelope.put("code", code);
        envelope.put("error", code);
        envelope.put("detail", detail);
        envelope.put("message", detail);
    }

    /**
     * 从 handler 结果推导工具日志和 envelope 使用的数值状态。
     *
     * @param failure dispatcher 返回的失败结果
     * @return 明确的数值状态或按错误内容推导的 4xx/5xx
     */
    private int dispatchStatus(Map<String, Object> failure) {
        Object value = failure.get("status");
        if (value instanceof Number number && number.intValue() >= 100) {
            return number.intValue();
        }
        String error = firstText(failure.get("code"), failure.get("error"), failure.get("detail"));
        if (error != null) {
            String normalized = error.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("provider") || normalized.contains("http ")
                    || normalized.contains("timeout") || normalized.contains("unavailable")) {
                return 503;
            }
        }
        return 400;
    }

    /**
     * 判断错误码是否为可供机器消费的短标识。
     *
     * @param value 候选错误码
     * @return 候选值是 ASCII slug 时为 true
     */
    private boolean stableCode(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    }

    /**
     * 从多个可能的错误字段中取第一个非空文本。
     *
     * @param values handler 返回字段
     * @return 第一个非空文本，全部为空时返回 null
     */
    private String firstText(Object... values) {
        for (Object value : values) {
            String text = textValue(value);
            if (text != null) return text;
        }
        return null;
    }

    /**
     * 把任意字段安全转换成非空文本。
     *
     * @param value 原始字段
     * @return 去首尾空白后的文本，空值返回 null
     */
    private String textValue(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
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
     * 为 discover 结果补充稳定的参数位置和必填字段提示。
     *
     * <p>operation catalog 仍是唯一 allowlist；这里的 schema 只帮助模型把参数放入
     * 正确的 query/body 容器，避免对版本查询、模型探测和索引调用反复试错。</p>
     */
    private Map<String, Object> operationDescription(OperationDefinition operation) {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("operation", operation.operation());
        description.put("method", operation.method());
        description.put("path", operation.path());
        description.put("summary", operation.summary());
        description.put("risk", operation.risk());
        description.put("parameter_schema", parameterSchema(operation));
        return description;
    }

    private Map<String, Object> parameterSchema(OperationDefinition operation) {
        String path = operation.path();
        String method = operation.method();
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("path_params", parameterSet(operation.pathParameterNames(), operation.pathParameterNames()));
        schema.put("query_params", parameterSet(List.of(), List.of()));
        schema.put("body", parameterSet(List.of(), List.of()));

        if ("GET".equals(method) && "/api/v1/files".equals(path)) {
            schema.put("query_params", parameterSet(
                    List.of("path", "q", "mode", "limit", "min_score", "type", "modified_after", "modified_before"),
                    List.of()));
        } else if ("GET".equals(method) && "/api/v1/files/stats".equals(path)) {
            schema.put("query_params", parameterSet(List.of("path"), List.of()));
        } else if ("GET".equals(method) && Set.of(
                "/api/v1/files/info", "/api/v1/files/content", "/api/v1/files/versions",
                "/api/v1/files/dedupe").contains(path)) {
            schema.put("query_params", parameterSet(
                    "/api/v1/files/dedupe".equals(path) ? List.of("md5") : List.of("path", "limit", "max_bytes"),
                    "/api/v1/files/dedupe".equals(path) ? List.of("md5") : List.of("path")));
        } else if ("GET".equals(method) && Set.of("/api/v1/files/favorites", "/api/v1/files/recent").contains(path)) {
            schema.put("query_params", parameterSet(List.of("limit"), List.of()));
        } else if ("GET".equals(method) && "/api/v1/index".equals(path)) {
            schema.put("query_params", parameterSet(List.of("prefix", "limit"), List.of()));
        } else if ("GET".equals(method) && "/api/v1/index/file".equals(path)) {
            schema.put("query_params", parameterSet(List.of("path"), List.of("path")));
        } else if ("PUT".equals(method) && Set.of("/api/v1/index/file", "/api/v1/index/vision").contains(path)) {
            schema.put("query_params", parameterSet(List.of("path"), List.of()));
            schema.put("body", parameterSet(List.of("paths", "files", "force"), List.of()));
        } else if ("PUT".equals(method) && "/api/v1/index/vectors".equals(path)) {
            schema.put("query_params", parameterSet(List.of("path"), List.of()));
            schema.put("body", parameterSet(List.of("paths", "files", "force", "limit"), List.of()));
        } else if ("POST".equals(method) && "/api/v1/index/rebuild".equals(path)) {
            schema.put("body", parameterSet(List.of("prefix"), List.of()));
        } else if ("POST".equals(method) && "/api/v1/config/models".equals(path)) {
            schema.put("body", parameterSet(List.of("type", "base_url", "api_key"), List.of("type", "base_url")));
        } else if ("POST".equals(method) && "/api/v1/config/test".equals(path)) {
            schema.put("body", parameterSet(List.of("type", "base_url", "api_key"), List.of("type", "base_url", "api_key")));
        } else if ("POST".equals(method) && "/api/v1/config".equals(path)) {
            schema.put("body", parameterSet(List.of("type", "base_url", "api_key", "model"), List.of("type", "base_url", "model")));
        } else if ("POST".equals(method) && "/api/v1/config/vision/models".equals(path)) {
            schema.put("body", parameterSet(List.of("provider", "base_url", "api_key"), List.of()));
        } else if ("PUT".equals(method) && "/api/v1/config/vision".equals(path)) {
            schema.put("body", parameterSet(List.of("provider", "base_url", "api_key", "model"), List.of("model")));
        } else if ("PUT".equals(method) && "/api/v1/config/embeddings".equals(path)) {
            schema.put("body", parameterSet(List.of("provider", "base_url", "api_key", "model"), List.of()));
        } else if ("POST".equals(method) && "/api/v1/vision/describe".equals(path)) {
            schema.put("body", parameterSet(List.of("files"), List.of("files")));
        } else if (Set.of("POST", "DELETE").contains(method) && Set.of(
                "/api/v1/files/favorites", "/api/v1/files/versions/restore", "/api/v1/files/mkdir",
                "/api/v1/files/delete", "/api/v1/files/trash/restore").contains(path)) {
            schema.put("body", parameterSet(
                    switch (path) {
                        case "/api/v1/files/favorites", "/api/v1/files/mkdir", "/api/v1/files/delete" -> List.of("path");
                        case "/api/v1/files/versions/restore" -> List.of("path", "version_id");
                        default -> List.of("trash_id", "path");
                    }, List.of()));
        } else if ("POST".equals(method) && Set.of("/api/v1/files/rename", "/api/v1/files/move", "/api/v1/files/copy").contains(path)) {
            schema.put("body", parameterSet(
                    switch (path) {
                        case "/api/v1/files/rename" -> List.of("src", "dst");
                        case "/api/v1/files/move" -> List.of("src", "dst_dir", "overwrite");
                        default -> List.of("src", "dst", "overwrite");
                    }, List.of()));
        }
        return schema;
    }

    private Map<String, Object> parameterSet(List<String> allowed, List<String> required) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allowed", List.copyOf(allowed));
        result.put("required", List.copyOf(required));
        return result;
    }

    private int operationStatus(RuntimeException error) {
        if (error instanceof com.agentdrive.files.FileStorageException storage) return storage.status();
        if (error instanceof IllegalArgumentException) return 400;
        String type = error.getClass().getName();
        if (type.contains("Unavailable") || type.contains("Timeout")) return 503;
        return 500;
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
