package com.agentdrive.api.chat;

import com.agentdrive.agent.AgentTool;
import com.agentdrive.agent.AgentToolContext;
import com.agentdrive.agent.BackendApiTool;
import com.agentdrive.agent.ConfirmationService;
import com.agentdrive.agent.OperationDefinition;
import com.agentdrive.agent.ToolReplayStore;
import com.agentdrive.agent.ChatTranscriptStore;
import com.agentdrive.agent.NoopChatTranscriptStore;
import com.agentdrive.agent.InMemoryToolReplayStore;
import com.agentdrive.agent.ConfiguredChatModel;
import com.agentdrive.agent.FixedProviderRuntimeResolver;
import com.agentdrive.agent.FrontendActionTool;
import com.agentdrive.agent.ProviderRuntimeResolver;
import com.agentdrive.agent.ChatRequestFactory;
import com.agentdrive.agent.ThinkingLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 LangChain4j 执行 Agent 对话、工具调用和 SSE 事件编排。
 *
 * <p>runtime 从 {@link ProviderRuntimeResolver} 按 owner 取得模型和请求工厂，把系统提示、
 * 客户端历史、当前消息和 {@link ChatContextProvider} 快照组装成模型上下文；后端读写工具经过 {@link BackendApiTool}，
 * 浏览器交互经过 {@link FrontendActionTool}，red 操作需要确认，非 red 操作按 session、
 * 工具名和参数执行确定性重放。会话消息和来源化上下文通过 transcript store 脱敏持久化，超过
 * {@code maxSteps} 时结束流并标记 truncated。
 */
public final class LangChainAgentRuntime implements ChatRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(LangChainAgentRuntime.class);
    private static final String TRUNCATION_MESSAGE = "工具步骤已达到上限，请继续发送消息。";
    private static final int DEFAULT_MAX_STEPS = 100;

    private final ProviderRuntimeResolver providerRuntimeResolver;
    private final Map<String, AgentTool> agentTools;
    private final ConfirmationService confirmationService;
    private final ToolReplayStore replayStore;
    private final ChatTranscriptStore transcriptStore;
    private final ChatContextProvider contextProvider;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;
    private final int maxSteps;
    private final List<ToolSpecification> toolSpecifications;

    /**
     * 创建使用随机确认签名和内存重放存储的兼容 runtime。
     *
     * @param model 固定的流式聊天模型。
     * @param backendApiTool 暴露给模型的 backend_api 工具。
     * @param objectMapper 解析工具参数和序列化工具结果的映射器。
     * @param requestFactory 将内部消息和思考等级转换为 Provider 请求。
     * @param systemPrompt 可配置系统提示；首尾会自动加 Agent Drive 身份保护。
     * @param maxSteps 单次对话允许的最大工具步骤数。
     */
    public LangChainAgentRuntime(
            StreamingChatModel model,
            BackendApiTool backendApiTool,
            ObjectMapper objectMapper,
            ChatRequestFactory requestFactory,
            String systemPrompt,
            int maxSteps
    ) {
        this(model, backendApiTool, objectMapper, requestFactory,
                ConfirmationService.random(objectMapper), systemPrompt, maxSteps);
    }

    /**
     * 创建使用内存重放存储的 runtime。
     *
     * @param model 固定的流式聊天模型。
     * @param backendApiTool 模型可调用的 backend_api 工具。
     * @param objectMapper 工具参数/结果 JSON 映射器。
     * @param requestFactory Provider 请求构造器。
     * @param confirmationService 签发、验证和消费 red 工具确认的服务。
     * @param systemPrompt 系统提示正文，首尾会加入身份保护。
     * @param maxSteps 单次对话的工具步数上限，必须为正数。
     */
    public LangChainAgentRuntime(
            StreamingChatModel model,
            BackendApiTool backendApiTool,
            ObjectMapper objectMapper,
            ChatRequestFactory requestFactory,
            ConfirmationService confirmationService,
            String systemPrompt,
            int maxSteps
    ) {
        this(model, backendApiTool, objectMapper, requestFactory, confirmationService,
                new InMemoryToolReplayStore(objectMapper), systemPrompt, maxSteps);
    }

    /**
     * 创建带有显式 Provider resolver 和可注入重放存储的 runtime。
     *
     * @param model 固定的流式聊天模型。
     * @param backendApiTool 模型可调用的 backend_api 工具。
     * @param objectMapper 工具参数/结果 JSON 映射器。
     * @param requestFactory Provider 请求构造器。
     * @param confirmationService red 操作确认服务。
     * @param replayStore 保存非 red 工具结果以支持确定性重放的存储。
     * @param systemPrompt 系统提示正文。
     * @param maxSteps 单次对话工具步骤上限。
     */
    public LangChainAgentRuntime(
            StreamingChatModel model,
            BackendApiTool backendApiTool,
            ObjectMapper objectMapper,
            ChatRequestFactory requestFactory,
            ConfirmationService confirmationService,
            ToolReplayStore replayStore,
            String systemPrompt,
            int maxSteps
    ) {
        this(new FixedProviderRuntimeResolver(new ConfiguredChatModel(model, requestFactory)),
                backendApiTool, objectMapper, confirmationService, replayStore,
                new NoopChatTranscriptStore(), systemPrompt, maxSteps);
    }

    /**
     * 创建使用 no-op transcript store 的 owner-aware runtime。
     *
     * @param providerRuntimeResolver 按认证 owner 解析 Provider 模型和请求工厂。
     * @param backendApiTool 模型可调用的 backend_api 工具。
     * @param objectMapper 工具参数/结果 JSON 映射器。
     * @param confirmationService red 操作确认服务。
     * @param replayStore 非 red 工具结果重放存储。
     * @param systemPrompt 系统提示正文。
     * @param maxSteps 单次对话工具步骤上限。
     */
    public LangChainAgentRuntime(
            ProviderRuntimeResolver providerRuntimeResolver,
            BackendApiTool backendApiTool,
            ObjectMapper objectMapper,
            ConfirmationService confirmationService,
            ToolReplayStore replayStore,
            String systemPrompt,
            int maxSteps
    ) {
        this(providerRuntimeResolver, List.of(backendApiTool, new FrontendActionTool(objectMapper)),
                objectMapper, confirmationService, replayStore,
                new NoopChatTranscriptStore(), systemPrompt, maxSteps);
    }

    /**
     * 创建由统一 Agent 工具适配器集合驱动的 runtime。
     *
     * <p>工具适配器只负责自己的 schema、参数校验和执行通道；runtime 只负责模型循环、
     * 风险确认、重放、transcript 和 SSE 编排。因此后端工具与浏览器工具使用同一套
     * runtime 契约，新增客户端通道不需要把业务分支写进聊天控制器。</p>
     *
     * @param providerRuntimeResolver 按认证 owner 解析 Provider 模型和请求工厂
     * @param tools 已注册的 Agent 工具适配器；工具名必须唯一
     * @param objectMapper 处理模型和工具 JSON 的映射器
     * @param confirmationService 持久化 red 操作确认并验证重放参数
     * @param replayStore 持久化非 red 工具调用结果
     * @param transcriptStore 脱敏写入 user、assistant、reasoning 和 tool trace
     * @param systemPrompt 可配置系统提示，首尾会加入身份保护
     * @param maxSteps 单次会话的工具步骤上限，必须大于 0
     * @throws NullPointerException 必需依赖为空时抛出
     * @throws IllegalArgumentException 工具名重复或 maxSteps 小于 1 时抛出
     */
    public LangChainAgentRuntime(
            ProviderRuntimeResolver providerRuntimeResolver,
            Collection<? extends AgentTool> tools,
            ObjectMapper objectMapper,
            ConfirmationService confirmationService,
            ToolReplayStore replayStore,
            ChatTranscriptStore transcriptStore,
            String systemPrompt,
            int maxSteps
    ) {
        this(providerRuntimeResolver, tools, objectMapper, confirmationService, replayStore,
                transcriptStore, ChatContextProvider.none(), systemPrompt, maxSteps);
    }

    /**
     * 创建带 owner 上下文装配的统一 Agent runtime。
     * @param providerRuntimeResolver 按认证 owner 解析 Provider runtime
     * @param tools 模型可见 Agent 工具
     * @param objectMapper 工具和响应 JSON 映射器
     * @param confirmationService red 操作确认服务
     * @param replayStore 非 red 工具重放存储
     * @param transcriptStore 会话消息和上下文持久化存储
     * @param contextProvider 系统、Agent 文档和 Skill 目录上下文 provider
     * @param systemPrompt 可配置系统提示
     * @param maxSteps 单次请求最大工具步骤
     */
    public LangChainAgentRuntime(
            ProviderRuntimeResolver providerRuntimeResolver,
            Collection<? extends AgentTool> tools,
            ObjectMapper objectMapper,
            ConfirmationService confirmationService,
            ToolReplayStore replayStore,
            ChatTranscriptStore transcriptStore,
            ChatContextProvider contextProvider,
            String systemPrompt,
            int maxSteps
    ) {
        this.providerRuntimeResolver = Objects.requireNonNull(
                providerRuntimeResolver, "providerRuntimeResolver must not be null");
        this.confirmationService = Objects.requireNonNull(confirmationService, "confirmationService must not be null");
        this.replayStore = Objects.requireNonNull(replayStore, "replayStore must not be null");
        this.transcriptStore = Objects.requireNonNull(transcriptStore, "transcriptStore must not be null");
        this.contextProvider = Objects.requireNonNull(contextProvider, "contextProvider must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.systemPrompt = AgentSystemPrompt.normalize(systemPrompt);
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        this.maxSteps = maxSteps;
        Map<String, AgentTool> byName = new LinkedHashMap<>();
        List<ToolSpecification> specifications = new ArrayList<>();
        for (AgentTool tool : Objects.requireNonNull(tools, "tools must not be null")) {
            AgentTool checked = Objects.requireNonNull(tool, "tool must not be null");
            if (byName.putIfAbsent(checked.toolName(), checked) != null) {
                throw new IllegalArgumentException("Duplicate Agent tool name: " + checked.toolName());
            }
            specifications.addAll(ToolSpecifications.toolSpecificationsFrom(checked));
        }
        this.agentTools = Map.copyOf(byName);
        this.toolSpecifications = List.copyOf(specifications);
    }

    /**
     * 创建生产 runtime 并校验全部持久化/安全依赖。
     *
     * @param providerRuntimeResolver 按 owner 解析当前 Provider runtime。
     * @param backendApiTool 注册工具定义并执行 backend API 调用。
     * @param objectMapper 处理模型和工具 JSON 的映射器。
     * @param confirmationService 持久化 red 操作确认并验证重放参数。
     * @param replayStore 持久化非 red 工具调用结果。
     * @param transcriptStore 脱敏写入 user、assistant、reasoning 和 tool trace。
     * @param systemPrompt 可配置系统提示，首尾会加入身份保护。
     * @param maxSteps 单次会话的工具步骤上限，必须大于 0。
     * @throws NullPointerException 任一必需依赖为空时抛出。
     * @throws IllegalArgumentException maxSteps 小于 1 时抛出。
     */
    public LangChainAgentRuntime(
            ProviderRuntimeResolver providerRuntimeResolver,
            BackendApiTool backendApiTool,
            ObjectMapper objectMapper,
            ConfirmationService confirmationService,
            ToolReplayStore replayStore,
            ChatTranscriptStore transcriptStore,
            String systemPrompt,
            int maxSteps
    ) {
        this(providerRuntimeResolver, List.of(backendApiTool, new FrontendActionTool(objectMapper)),
                objectMapper, confirmationService, replayStore, transcriptStore, systemPrompt, maxSteps);
    }

    /**
     * 创建使用默认系统提示和默认一百步上限的兼容 runtime。
     *
     * @param model 固定的流式聊天模型。
     * @param backendApiTool 模型可调用的 backend_api 工具。
     * @param objectMapper 工具参数/结果 JSON 映射器。
     * @param requestFactory Provider 请求构造器。
     */
    public LangChainAgentRuntime(
            StreamingChatModel model,
            BackendApiTool backendApiTool,
            ObjectMapper objectMapper,
            ChatRequestFactory requestFactory
    ) {
        this(model, backendApiTool, objectMapper, requestFactory, "", DEFAULT_MAX_STEPS);
    }

    /**
     * 执行流式 runtime 并收集事件，聚合为非流式响应。
     *
     * @param request 已包含认证 owner 的聊天请求。
     * @return 收集完成后发出一个聊天结果；流内错误按 Reactor 错误传播。
     */
    @Override
    public Mono<ChatResponse> complete(ChatRequest request) {
        return stream(request).collectList().map(this::aggregate);
    }

    /**
     * 创建一次可取消的 Agent SSE 会话。
     *
     * @param request 已包含认证 owner 的聊天请求。
     * @return 由内部 StreamSession 发出的事件流；订阅取消会停止后续事件。
     */
    @Override
    public Flux<ChatSseEvent> stream(ChatRequest request) {
        return Flux.create(sink -> new StreamSession(request, sink).start(), FluxSink.OverflowStrategy.BUFFER);
    }

    /**
     * 从 stream 事件中重建非流式聊天结果。
     *
     * @param events 已完成的事件序列；正文来自 text，工具轨迹来自 tool_trace，统计来自 done。
     * @return 聚合的回复、工具轨迹、统计、会话和截断状态。
     */
    private ChatResponse aggregate(List<ChatSseEvent> events) {
        StringBuilder reply = new StringBuilder();
        List<Map<String, Object>> traces = new ArrayList<>();
        Map<String, Object> done = Map.of();
        for (ChatSseEvent event : events) {
            if ("text".equals(event.event())) {
                reply.append(String.valueOf(event.data().getOrDefault("text", "")));
            } else if ("tool_trace".equals(event.event())) {
                traces.add(new LinkedHashMap<>(event.data()));
            } else if ("done".equals(event.event())) {
                done = event.data();
            }
        }
        return new ChatResponse(
                reply.toString(),
                traces.isEmpty() ? listOfMaps(done.get("tool_trace")) : List.copyOf(traces),
                intValue(done.get("steps")),
                longValue(done.get("latency_ms")),
                mapValue(done.get("pending_confirmation")),
                stringValue(done.get("session_id")),
                booleanValue(done.get("needs_summary")),
                stringValue(done.get("routed")),
                listOfMaps(done.get("plan")),
                nonNullMap(done.get("usage")),
                nonNullMap(done.get("context_usage")),
                booleanValue(done.get("truncated"))
        );
    }

    /**
     * 管理一次模型调用、工具循环和 SSE 终态的有状态会话。
     *
     * <p>该对象只属于一次订阅：保存消息上下文、回复缓冲、工具轨迹、待确认操作和
     * terminal 标志。所有终态都通过原子标志保证只发送一次 done 或 error，客户端取消
     * 订阅时停止继续调用模型。
     */
    private final class StreamSession {
        private final ChatRequest input;
        private final FluxSink<ChatSseEvent> sink;
        private StreamingChatModel model;
        private ChatRequestFactory requestFactory;
        private final List<ChatMessage> messages = new ArrayList<>();
        private final List<Map<String, Object>> traces = new ArrayList<>();
        private final StringBuilder reply = new StringBuilder();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final long startedAt = System.nanoTime();
        private int steps;
        private int modelSteps;
        private Map<String, Object> pendingConfirmation;

        /**
         * 创建一次流式 Agent 会话并注册取消处理。
         *
         * @param input 已规范化的聊天请求。
         * @param sink 向下游发送聊天事件的 Flux sink。
         * @throws NullPointerException input 为空时抛出。
         */
        private StreamSession(ChatRequest input, FluxSink<ChatSseEvent> sink) {
            this.input = Objects.requireNonNull(input, "request must not be null");
            this.sink = sink;
            sink.onCancel(() -> {
                if (terminal.compareAndSet(false, true)) {
                    LOGGER.info("chat_stream_cancel request_id={} session_id={} owner={} route=runtime steps={} model_steps={} duration_ms={}",
                            requestId(), safeId(input.sessionId()), ownerId(), steps, modelSteps, elapsedMillis());
                }
            });
        }

        /**
         * 解析当前 owner 的 Provider，记录用户消息并启动模型循环。
         *
         * <p>Provider 解析、transcript 写入和上下文构造的任一异常都会进入统一 fail
         * 路径，不会继续调用模型。
         */
        private void start() {
            try {
                ConfiguredChatModel configured = providerRuntimeResolver.resolve(
                        input.authenticatedUserId(), input.model());
                model = configured.model();
                requestFactory = configured.requestFactory();
                LOGGER.info("chat_provider_resolved request_id={} session_id={} owner={} route={} provider={} model={} model_impl={}",
                        requestId(), safeId(input.sessionId()), ownerId(), route(), safeId(configured.provider()),
                        safeId(configured.modelName()), configured.model().getClass().getSimpleName());
                transcriptStore.appendUser(input.sessionId(), input.message());
                appendMessages();
                continueModel();
            } catch (Throwable error) {
                fail(error);
            }
        }

        /**
         * 将系统提示、客户端历史、当前消息和 owner 上下文转换为 LangChain4j 消息列表。
         *
         * <p>只接受带非空 content 的 user 和 assistant 历史项，工具消息和未知 role
         * 不从客户端历史注入，避免客户端伪造内部工具上下文。
         */
        private void appendMessages() {
            if (!systemPrompt.isBlank()) {
                messages.add(SystemMessage.from(systemPrompt));
            }
            for (Map<String, Object> item : input.history()) {
                String content = stringValue(item.get("content"));
                if (content == null || content.isBlank()) {
                    continue;
                }
                String role = stringValue(item.get("role"));
                if ("assistant".equals(role)) {
                    messages.add(AiMessage.from(content));
                } else if ("user".equals(role)) {
                    messages.add(UserMessage.from(content));
                }
            }
            messages.add(UserMessage.from(input.message()));
            for (ChatContext context : contextProvider.contexts(input.authenticatedUserId())) {
                if (context.userMessage()) {
                    messages.add(UserMessage.from(context.content()));
                }
                if (transcriptStore.appendContextIfChanged(
                        input.sessionId(), context.source(), context.kind(), context.content())) {
                    sink.next(ChatSseEvents.context(context));
                }
            }
        }

        /**
         * 在未终止且未超过步数上限时创建下一次 Provider 请求。
         *
         * <p>请求包含当前消息、已注册工具规格和用户思考等级；达到上限时发送截断正文
         * 并完成会话，不再调用模型。
         */
        private void continueModel() {
            if (terminal.get()) {
                return;
            }
            if (steps >= maxSteps) {
                emitText(TRUNCATION_MESSAGE);
                finish(true);
                return;
            }
            dev.langchain4j.model.chat.request.ChatRequest request = requestFactory.create(
                    List.copyOf(messages),
                    toolSpecifications,
                    ThinkingLevel.from(input.thinkingLevel())
            );
            int modelStep = ++modelSteps;
            long modelStartedAt = System.nanoTime();
            LOGGER.info("chat_model_step_start request_id={} session_id={} owner={} route={} model_step={} tool_steps={} message_count={} tool_count={} thinking_level={}",
                    requestId(), safeId(input.sessionId()), ownerId(), route(), modelStep, steps, messages.size(),
                    toolSpecifications.size(), input.thinkingLevel());
            try {
                model.chat(request, new ModelHandler(modelStep, modelStartedAt));
            } catch (Throwable error) {
                LOGGER.error("chat_model_step_error request_id={} session_id={} owner={} route={} model_step={} duration_ms={}",
                        requestId(), safeId(input.sessionId()), ownerId(), route(), modelStep,
                        elapsedMillis(modelStartedAt), ChatLogSupport.safeThrowable(error));
                fail(error);
            }
        }

        /**
         * 校验确认、执行或重放一个模型工具请求，并把结果加入上下文。
         *
         * <p>red 操作没有有效确认时只保存待确认参数并结束当前轮；非 red 操作优先
         * 查询重放存储，否则调用 backend_api、解析完整输出、保存重放结果并发送工具轨迹。
         * 工具结果始终以 ToolExecutionResultMessage 追加到下一轮模型上下文。
         *
         * @param request LangChain4j 返回的工具执行请求。
         * @return 工具已执行并可继续模型循环时为 {@code true}；等待确认或会话已结束时为 {@code false}。
         */
        private boolean executeTool(ToolExecutionRequest request) {
            Map<String, Object> arguments = arguments(request.arguments());
            if (needsConfirmation(request, arguments)) {
                Map<String, Object> pending = confirmationService.findIssued(input.sessionId(), request.name(), arguments);
                if (pending == null) {
                    pending = confirmationService.issue(input.sessionId(), request.name(), arguments);
                } else if (confirmationService.verifyAndConsume(input.sessionId(), pending, input.confirmations())) {
                    pending = null;
                }
                if (pending != null) {
                    pendingConfirmation = pending;
                    finish(false);
                    return false;
                }
            }

            int step = ++steps;
            sink.next(ChatSseEvents.toolStart(step, request.name(), arguments));
            OperationDefinition definition = definitionFor(request, arguments);
            long toolStartedAt = System.nanoTime();
            LOGGER.info("chat_tool_start request_id={} session_id={} owner={} route={} step={} tool={} action={} operation={} path={} parameter_keys={}",
                    requestId(), safeId(input.sessionId()), ownerId(), route(), step, safeId(request.name()),
                    action(arguments), definition == null ? "-" : safeId(definition.operation()),
                    definition == null ? "-" : safeId(definition.path()), parameterKeys(arguments));

            String output;
            Map<String, Object> parsed;
            boolean replayed = false;
            ToolReplayStore.ToolReplay replay = replayFor(request, arguments);
            if (replay != null) {
                output = replay.output();
                parsed = replay.parsed();
                replayed = true;
            } else {
                try {
                    output = invokeTool(request);
                    parsed = parseToolOutput(output);
                } catch (Exception error) {
                    LOGGER.error("chat_tool_error request_id={} session_id={} owner={} route={} step={} tool={} operation={} duration_ms={}",
                            requestId(), safeId(input.sessionId()), ownerId(), route(), step, safeId(request.name()),
                            definition == null ? "-" : safeId(definition.operation()), elapsedMillis(toolStartedAt),
                            ChatLogSupport.safeThrowable(error));
                    output = errorOutput(error);
                    parsed = parseToolOutput(output);
                }
                replayStore.save(input.sessionId(), request.name(), arguments, output, parsed);
            }

            ChatSseEvent trace = ChatSseEvents.toolTrace(
                    step,
                    request.name(),
                    arguments,
                    output,
                    parsed,
                    false,
                    replayed
            );
            traces.add(new LinkedHashMap<>(trace.data()));
            transcriptStore.appendToolTrace(input.sessionId(), request.name(), arguments, output, parsed);
            sink.next(trace);
            AgentTool tool = agentTools.get(request.name());
            if (tool != null) {
                Map<String, Object> clientEvent = tool.clientEvent(parsed);
                if (clientEvent != null) {
                    sink.next(ChatSseEvents.frontendAction(clientEvent));
                }
            }
            messages.add(ToolExecutionResultMessage.from(request, output));
            LOGGER.info("chat_tool_end request_id={} session_id={} owner={} route={} step={} tool={} operation={} http_status={} duration_ms={} replayed={}",
                    requestId(), safeId(input.sessionId()), ownerId(), route(), step, safeId(request.name()),
                    definition == null ? "-" : safeId(definition.operation()), statusCode(parsed, definition),
                    elapsedMillis(toolStartedAt), replayed);
            return true;
        }

        /**
         * 根据当前 Agent 工具适配器返回的 operation 风险等级判断是否需要用户确认。
         *
         * @param request 模型产生的工具请求。
         * @param arguments 已解析的工具参数，用于查找 operation 定义。
         * @return operation 存在且风险为 red 时为 {@code true}。
         */
        private boolean needsConfirmation(ToolExecutionRequest request, Map<String, Object> arguments) {
            OperationDefinition definition = definitionFor(request, arguments);
            return definition != null && "red".equalsIgnoreCase(definition.risk());
        }

        /**
         * 查找可安全重放的非 red 工具结果。
         *
         * @param request 模型产生的工具请求。
         * @param arguments 已解析的精确参数。
         * @return 按 session、工具名和参数命中的重放结果；未知 operation 或 red 操作为 {@code null}。
         */
        private ToolReplayStore.ToolReplay replayFor(ToolExecutionRequest request, Map<String, Object> arguments) {
            OperationDefinition definition = definitionFor(request, arguments);
            if (definition == null || "red".equalsIgnoreCase(definition.risk())) {
                return null;
            }
            return replayStore.find(input.sessionId(), request.name(), arguments);
        }

        /**
         * 将模型工具参数转换为注册工具适配器提供的 operation 定义。
         *
         * @param request 模型工具请求；工具名必须在 Agent 工具注册表中。
         * @param arguments 已解析的参数映射。
         * @return catalog 中匹配的 operation 定义；工具名、参数格式或 operation 不合法时返回 {@code null}。
         */
        private OperationDefinition definitionFor(ToolExecutionRequest request, Map<String, Object> arguments) {
            AgentTool tool = agentTools.get(request.name());
            if (tool == null) {
                return null;
            }
            try {
                return tool.definitionFor(arguments, toolContext());
            } catch (IllegalArgumentException error) {
                return null;
            }
        }

        /**
         * 解析并调用当前工具注册表中的适配器。
         *
         * @param request 模型生成的工具请求。
         * @return 工具适配器返回的原始 JSON 文本；工具名未登记时返回 JSON 错误。
         * @throws Exception 工具参数或适配器执行失败时抛出。
         */
        private String invokeTool(ToolExecutionRequest request) throws Exception {
            AgentTool tool = agentTools.get(request.name());
            if (tool == null) {
                return errorOutput(new IllegalArgumentException("tool is not registered: " + request.name()));
            }
            return tool.executeRaw(request.arguments(), toolContext());
        }

        /**
         * 创建当前模型工具调用使用的服务端上下文。
         *
         * @return owner、request ID 和浏览器能力清单组成的工具上下文
         */
        private AgentToolContext toolContext() {
            return new AgentToolContext(input.authenticatedUserId(), requestId(), input.frontendCapabilities());
        }

        /**
         * 解析模型提供的工具参数 JSON。
         *
         * @param raw 工具请求中的 JSON 参数文本。
         * @return 对象参数映射；空文本返回空对象，非法 JSON 返回带 {@code _invalid_json} 的标记对象。
         */
        private Map<String, Object> arguments(String raw) {
            if (raw == null || raw.isBlank()) {
                return Map.of();
            }
            try {
                return objectMapper.readValue(raw, new TypeReference<>() { });
            } catch (JsonProcessingException error) {
                return Map.of("_invalid_json", true);
            }
        }

        /**
         * 从工具完整输出解析结构化展示结果。
         *
         * @param output 工具返回的完整 JSON 文本。
         * @return 对象 JSON 转成字段映射，标量包装在 {@code value} 中，非法 JSON 返回错误映射。
         */
        private Map<String, Object> parseToolOutput(String output) {
            try {
                JsonNode node = objectMapper.readTree(output);
                if (node != null && node.isObject()) {
                    return objectMapper.convertValue(node, new TypeReference<>() { });
                }
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("value", node == null ? null : objectMapper.convertValue(node, Object.class));
                return value;
            } catch (JsonProcessingException error) {
                return Map.of("ok", false, "error", "invalid_tool_output");
            }
        }

        /**
         * 将工具执行异常编码成稳定 JSON 错误文本。
         *
         * @param error 工具调用或结果处理阶段的异常。
         * @return 含 {@code tool_execution_failed} 和错误消息的 JSON；JSON 编码失败时使用固定兜底文本。
         */
        private String errorOutput(Throwable error) {
            String message = ChatLogSupport.message(error);
            try {
                return objectMapper.writeValueAsString(Map.of(
                        "ok", false,
                        "error", "tool_execution_failed",
                        "message", message == null ? "tool execution failed" : message
                ));
            } catch (JsonProcessingException encodingError) {
                return "{\"ok\":false,\"error\":\"tool_execution_failed\"}";
            }
        }

        /**
         * 追加回复缓冲并发送一个正文 SSE 片段。
         *
         * @param text Provider 返回的非空正文片段；会话已终止时丢弃。
         */
        private void emitText(String text) {
            if (text == null || text.isEmpty() || terminal.get()) {
                return;
            }
            reply.append(text);
            sink.next(ChatSseEvents.text(text));
        }

        /**
         * 原子地结束会话并发送 done 事件。
         *
         * <p>结束前写入最后工具轨迹；只有存在会话、正常完成、没有待确认且正文非空时
         * 才标记 {@code needs_summary}。重复终态调用会被忽略。
         *
         * @param truncated 是否因工具步数上限结束。
         */
        private void finish(boolean truncated) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            transcriptStore.updateLastTrace(input.sessionId(), List.copyOf(traces));
            boolean needsSummary = input.sessionId() != null && !input.sessionId().isBlank()
                    && !truncated && pendingConfirmation == null && !reply.toString().isBlank();
            ChatResponse response = new ChatResponse(
                    reply.toString(),
                    List.copyOf(traces),
                    steps,
                    (System.nanoTime() - startedAt) / 1_000_000,
                    pendingConfirmation,
                    input.sessionId(),
                    needsSummary,
                    traces.isEmpty() ? "chat" : "task",
                    List.of(),
                    Map.of(),
                    Map.of(),
                    truncated
            );
            sink.next(ChatSseEvents.done(response));
            LOGGER.info("chat_terminal_done request_id={} session_id={} owner={} route={} terminal=done steps={} model_steps={} tool_count={} truncated={} duration_ms={}",
                    requestId(), safeId(input.sessionId()), ownerId(), traces.isEmpty() ? "chat" : "task", steps,
                    modelSteps, traces.size(), truncated, elapsedMillis());
            sink.complete();
        }

        /**
         * 原子地终止会话并向下游传播 runtime 异常。
         *
         * @param error Provider、工具或持久化阶段的异常。
         */
        private void fail(Throwable error) {
            if (terminal.compareAndSet(false, true)) {
                String message = ChatLogSupport.message(error);
                if (input.sessionId() != null && !input.sessionId().isBlank()) {
                    try {
                        transcriptStore.appendAssistant(input.sessionId(), "出错了：" + message, "");
                        transcriptStore.updateLastTrace(input.sessionId(), List.copyOf(traces));
                    } catch (RuntimeException persistenceError) {
                        LOGGER.warn("chat_error_transcript_write_failed request_id={} session_id={} owner={} route={}",
                                requestId(), safeId(input.sessionId()), ownerId(), route(),
                                ChatLogSupport.safeThrowable(persistenceError));
                    }
                }
                LOGGER.error("chat_terminal_error request_id={} session_id={} owner={} route={} terminal=error steps={} model_steps={} duration_ms={}",
                        requestId(), safeId(input.sessionId()), ownerId(), route(), steps, modelSteps, elapsedMillis(),
                        ChatLogSupport.safeThrowable(error));
                sink.error(error);
            }
        }

        /**
         * 接收 LangChain4j 当前轮模型回调并驱动文本、思考、工具和终态事件。
         *
         * <p>该处理器只属于外层 StreamSession，模型完成后会把 assistant 消息写入
         * transcript；若包含工具请求则执行工具后继续模型循环，否则完成当前会话。
         */
        private final class ModelHandler implements StreamingChatResponseHandler {
            private final StringBuilder turnText = new StringBuilder();
            private final StringBuilder turnReasoning = new StringBuilder();
            private final int modelStep;
            private final long modelStartedAt;
            private boolean thinkingEmitted;

            /**
             * 绑定当前 Provider 回调所属的模型轮次和起始时间。
             *
             * @param modelStep 当前模型轮次编号
             * @param modelStartedAt 当前轮单调时钟起点
             */
            private ModelHandler(int modelStep, long modelStartedAt) {
                this.modelStep = modelStep;
                this.modelStartedAt = modelStartedAt;
            }

            /**
             * 转发 Provider 的正文增量并累积当前轮文本。
             *
             * @param partialResponse Provider 返回的正文片段；空片段不发送。
             */
            @Override
            public void onPartialResponse(String partialResponse) {
                if (partialResponse != null && !partialResponse.isEmpty()) {
                    turnText.append(partialResponse);
                    emitText(partialResponse);
                }
            }

            /**
             * 转发 Provider 独立的 reasoning 增量，不把它混入回复正文。
             *
             * @param partialThinking Provider 返回的思考片段；空值或空文本忽略。
             */
            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                if (partialThinking != null && partialThinking.text() != null
                        && !partialThinking.text().isEmpty()) {
                    thinkingEmitted = true;
                    turnReasoning.append(partialThinking.text());
                    sink.next(ChatSseEvents.reasoning(partialThinking.text()));
                }
            }

            /**
             * 处理当前轮完整模型响应。
             *
             * <p>若流式回调没有 reasoning，则从完整 AiMessage 补发；写入 assistant transcript
             * 后执行模型请求的工具调用并继续循环，或在无工具时发送 done。
             *
             * @param response LangChain4j 当前轮响应，可为空。
             */
            @Override
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response) {
                if (terminal.get()) {
                    return;
                }
                AiMessage aiMessage = response == null ? null : response.aiMessage();
                if (aiMessage == null) {
                    LOGGER.info("chat_model_step_end request_id={} session_id={} owner={} route={} model_step={} tool_calls=0 text_chars=0 reasoning_chars=0 duration_ms={}",
                            requestId(), safeId(input.sessionId()), ownerId(), route(), modelStep, elapsedMillis(modelStartedAt));
                    finish(false);
                    return;
                }
                if (!thinkingEmitted && aiMessage.thinking() != null && !aiMessage.thinking().isBlank()) {
                    turnReasoning.append(aiMessage.thinking());
                    sink.next(ChatSseEvents.reasoning(aiMessage.thinking()));
                }
                if (turnText.isEmpty() && aiMessage.text() != null && !aiMessage.text().isEmpty()) {
                    emitText(aiMessage.text());
                }
                String assistantText = turnText.isEmpty() ? aiMessage.text() : turnText.toString();
                transcriptStore.appendAssistant(input.sessionId(), assistantText, turnReasoning.toString());
                messages.add(aiMessage);
                LOGGER.info("chat_model_step_end request_id={} session_id={} owner={} route={} model_step={} tool_calls={} text_chars={} reasoning_chars={} duration_ms={}",
                        requestId(), safeId(input.sessionId()), ownerId(), route(), modelStep,
                        aiMessage.toolExecutionRequests().size(), turnText.length(), turnReasoning.length(),
                        elapsedMillis(modelStartedAt));
                if (aiMessage.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                        if (!executeTool(request)) {
                            return;
                        }
                    }
                    continueModel();
                } else {
                    finish(false);
                }
            }

            /**
             * 将 Provider 流错误交给外层会话终止逻辑。
             *
             * @param error Provider 返回的异常。
             */
            @Override
            public void onError(Throwable error) {
                LOGGER.error("chat_model_step_error request_id={} session_id={} owner={} route={} model_step={} duration_ms={}",
                        requestId(), safeId(input.sessionId()), ownerId(), route(), modelStep,
                        elapsedMillis(modelStartedAt), ChatLogSupport.safeThrowable(error));
                fail(error);
            }
        }

        /**
         * 返回当前聊天流的 request id，内部测试和非 HTTP 调用使用短横线占位。
         *
         * @return 安全日志关联 ID
         */
        private String requestId() {
            return safeId(input.requestId());
        }

        /**
         * 返回当前聊天 owner 的可检索标识。
         *
         * @return owner UUID 或 anonymous
         */
        private String ownerId() {
            return input.authenticatedUserId() == null ? "anonymous" : input.authenticatedUserId().toString();
        }

        /**
         * 根据已产生工具轨迹标记当前运行分支。
         *
         * @return 尚未产生工具时为 chat，否则为 task
         */
        private String route() {
            return traces.isEmpty() ? "chat" : "task";
        }

        /**
         * 计算当前流从创建到现在的耗时。
         *
         * @return 非负耗时毫秒
         */
        private long elapsedMillis() {
            return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        }

        /**
         * 计算指定模型或工具阶段的耗时。
         *
         * @param startedAt 阶段单调时钟起点
         * @return 非负耗时毫秒
         */
        private long elapsedMillis(long startedAt) {
            return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        }

        /**
         * 把聊天关联标识限制为日志安全字符集。
         *
         * @param value 原始关联标识
         * @return 短的安全标识
         */
        private String safeId(String value) {
            if (value == null || value.isBlank()) {
                return "-";
            }
            String safe = value.replaceAll("[^A-Za-z0-9._:/-]", "_");
            return safe.substring(0, Math.min(safe.length(), 128));
        }

        /**
         * 只输出工具参数键名，不输出用户或凭据值。
         *
         * @param arguments 已解析的工具参数
         * @return 排序后的顶层参数键名
         */
        private String parameterKeys(Map<String, Object> arguments) {
            return arguments.keySet().stream().sorted().toList().toString();
        }

        /**
         * 读取工具 action 字段，缺失时用短横线表示。
         *
         * @param arguments 工具参数
         * @return discover 或 call 等 action 名
         */
        private String action(Map<String, Object> arguments) {
            Object value = arguments.get("action");
            return value == null ? "-" : safeId(String.valueOf(value));
        }

        /**
         * 从工具结果映射得到可检索状态码；内部工具没有 HTTP 状态。
         *
         * @param parsed 已解析工具响应
         * @param definition operation 定义
         * @return 显式状态、成功 200、错误 500 或内部操作 0
         */
        private int statusCode(Map<String, Object> parsed, OperationDefinition definition) {
            if (definition != null && "INTERNAL".equals(definition.method())) {
                return 0;
            }
            Object status = parsed.get("status");
            if (status instanceof Number number) {
                return number.intValue();
            }
            return Boolean.FALSE.equals(parsed.get("ok")) ? 500 : 200;
        }
    }

    /**
     * 从 done 数据读取整数统计。
     *
     * @param value 待转换对象。
     * @return Number 的整数值，其他类型返回 0。
     */
    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * 从 done 数据读取长整数统计。
     *
     * @param value 待转换对象。
     * @return Number 的长整数值，其他类型返回 0。
     */
    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * 从 done 数据读取布尔标志。
     *
     * @param value 待转换对象。
     * @return 仅当对象是 Boolean 且为 true 时返回 true。
     */
    private static boolean booleanValue(Object value) {
        return value instanceof Boolean bool && bool;
    }

    /**
     * 将完成数据中的可选值转换为字符串。
     *
     * @param value 待转换对象。
     * @return null 保持为 null，否则返回 String.valueOf 结果。
     */
    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 将任意 map 键规范化为字符串键。
     *
     * @param value 待转换对象。
     * @return 转换后的可变字符串键映射；输入不是 Map 时返回 null。
     */
    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    /**
     * 将可选 map 统计规范化为非空映射。
     *
     * @param value 待转换对象。
     * @return mapValue 结果，或输入不是 Map 时的不可变空映射。
     */
    private static Map<String, Object> nonNullMap(Object value) {
        Map<String, Object> result = mapValue(value);
        return result == null ? Map.of() : result;
    }

    /**
     * 从完成数据中提取 map 列表并过滤非 map 项。
     *
     * @param value 待转换的列表对象。
     * @return 每个元素都具有字符串键的不可变映射列表；输入不是列表时返回空列表。
     */
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> source)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : source) {
            Map<String, Object> mapped = mapValue(item);
            if (mapped != null) {
                result.add(mapped);
            }
        }
        return List.copyOf(result);
    }
}
