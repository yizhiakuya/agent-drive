package com.agentdrive.api.chat;

import com.agentdrive.api.ReactiveExecution;
import com.agentdrive.api.WebRequestMetadata;
import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.auth.ConversationSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.agentdrive.api.ReactiveExecution.blocking;

/**
 * 暴露非流式 {@code POST /api/v1/chat} 和流式 {@code POST /api/v1/chat/stream}。
 *
 * <p>在完整 Java chat profile 下，控制器先认证请求并通过会话服务创建/确认 owner-scoped
 * session ID，再调用 {@link ChatRuntime}。流式响应明确设置 no-cache 和
 * {@code X-Accel-Buffering=no}，runtime 异常被转换为 SSE error 事件并保持已建立的
 * HTTP 流协议；兼容测试构造器允许没有会话服务时直接转发请求。
 */
@RestController
@Profile("java-chat")
@RequestMapping("/api/v1")
public final class ChatController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatController.class);
    private static final String SESSION_ID_HEADER = "X-Session-ID";
    private static final Duration COMPLETE_RUN_TIMEOUT = Duration.ofMinutes(10);
    private final ChatRuntime runtime;
    private final ChatSseEncoder encoder;
    private final ConversationSessionService sessionService;
    private final WebRequestPrincipalResolver principalResolver;
    private final ChatRunRegistry runRegistry;

    /**
     * 创建不注入会话服务的兼容控制器。
     *
     * @param runtime 执行聊天完成和流式生成的 runtime。
     * @param objectMapper 编码 SSE data JSON 的映射器。
     */
    public ChatController(ChatRuntime runtime, ObjectMapper objectMapper) {
        this(runtime, objectMapper, null, null, null);
    }

    /**
     * 创建不注入任务存储的兼容控制器，供已有非后台聊天测试使用。
     * @param runtime 执行聊天的 runtime
     * @param objectMapper SSE JSON 映射器
     * @param sessionService owner-scoped 会话服务
     * @param principalResolver 请求认证解析器
     */
    public ChatController(ChatRuntime runtime, ObjectMapper objectMapper,
                          ConversationSessionService sessionService,
                          WebRequestPrincipalResolver principalResolver) {
        this(runtime, objectMapper, sessionService, principalResolver, null);
    }

    /**
     * 创建聊天控制器并保存聊天、会话和认证依赖。
     *
     * @param runtime 执行 Agent 的聊天 runtime。
     * @param objectMapper 将事件 data 序列化为 JSON 的映射器。
     * @param sessionService 确保会话属于当前 owner 并持久化会话消息的服务，可为兼容构造留空。
     * @param principalResolver 解析当前请求 owner 的认证组件，可为兼容构造留空。
     */
    @Autowired
    public ChatController(ChatRuntime runtime,
                          ObjectMapper objectMapper,
                          ConversationSessionService sessionService,
                          WebRequestPrincipalResolver principalResolver,
                          ChatRunRegistry runRegistry) {
        this.runtime = runtime;
        this.encoder = new ChatSseEncoder(objectMapper);
        this.sessionService = sessionService;
        this.principalResolver = principalResolver;
        this.runRegistry = runRegistry;
    }

    /**
     * 响应 {@code POST /api/v1/chat}，执行一次非流式聊天。
     *
     * @param request 已通过 Bean Validation 的聊天请求。
     * @param exchange 用于认证 owner 和确保会话归属的请求上下文。
     * @return runtime 聚合的聊天结果。
     */
    @PostMapping("/chat")
    public Mono<ChatResponse> complete(@Valid @RequestBody ChatRequest request,
                                       ServerWebExchange exchange) {
        return prepare(request, exchange)
                .flatMap(normalized -> ReactiveExecution.onBlockingScheduler(
                        () -> {
                            if (runRegistry == null) {
                                return runtime.complete(normalized).timeout(COMPLETE_RUN_TIMEOUT);
                            }
                            return runRegistry.start(normalized, runtime)
                                    .collectList()
                                    .map(LangChainAgentRuntime::aggregateEvents)
                                    .timeout(COMPLETE_RUN_TIMEOUT);
                        }));
    }

    /**
     * 重连当前 owner 会话的内存 Agent relay；页面刷新后可继续接收未完成的事件。
     * @param sessionId owner-scoped session ID
     * @param exchange 当前认证请求
     * @return replay relay，若没有活跃运行则为空流
     */
    @GetMapping(value = "/chat/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> reconnect(@PathVariable String sessionId,
                                                                  ServerWebExchange exchange) {
        if (runRegistry == null || sessionService == null || principalResolver == null) return Flux.empty();
        return principalResolver.resolve(exchange).flatMapMany(principal ->
                blocking(() -> {
                    sessionService.getOwned(principal.userId(), sessionId);
                    return true;
                }).flatMapMany(ignored -> runRegistry.reconnect(sessionId)
                        .map(event -> ServerSentEvent.<Map<String, Object>>builder(event.data())
                                .event(event.event()).build())));
    }

    /** 查询当前 owner 会话是否有活跃 Agent。 */
    @GetMapping("/chat/{sessionId}/active")
    public Mono<Map<String, Object>> active(@PathVariable String sessionId, ServerWebExchange exchange) {
        if (runRegistry == null || sessionService == null || principalResolver == null) {
            return Mono.just(Map.of("active", false));
        }
        return principalResolver.resolve(exchange).flatMap(principal ->
                blocking(() -> {
                    sessionService.getOwned(principal.userId(), sessionId);
                    return runRegistry.state(sessionId);
                }));
    }

    /** 主动停止当前 owner 会话的运行；浏览器刷新本身不会调用此接口。 */
    @PostMapping("/chat/{sessionId}/cancel")
    public Mono<Map<String, Object>> cancel(@PathVariable String sessionId,
                                             ServerWebExchange exchange) {
        if (runRegistry == null || sessionService == null || principalResolver == null) {
            return Mono.just(Map.of("cancelled", false));
        }
        return principalResolver.resolve(exchange).flatMap(principal ->
                blocking(() -> {
                    sessionService.getOwned(principal.userId(), sessionId);
                    return Map.of("cancelled", runRegistry.cancel(sessionId));
                }));
    }

    /**
     * 响应 {@code POST /api/v1/chat/stream}，把 runtime 事件编码为 SSE 流。
     *
     * @param request 已通过 Bean Validation 的聊天请求。
     * @param exchange 用于认证 owner 和确保会话归属的请求上下文。
     * @param response 用于设置 SSE、缓存和代理缓冲响应头并写入事件帧。
     * @return 响应写入完成或连接终止时结束的异步信号。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Mono<Void> stream(@Valid @RequestBody ChatRequest request,
                             ServerWebExchange exchange,
                             ServerHttpResponse response) {
        String requestId = WebRequestMetadata.requestId(exchange);
        response.getHeaders().set(WebRequestMetadata.REQUEST_ID_HEADER, requestId);
        return prepare(request, exchange).map(normalized -> normalized.withRequestId(requestId)).flatMap(normalized -> {
            if (normalized.sessionId() != null) response.getHeaders().set(SESSION_ID_HEADER, normalized.sessionId());
            HttpHeaders headers = response.getHeaders();
            headers.setContentType(MediaType.TEXT_EVENT_STREAM);
            headers.setCacheControl(CacheControl.noCache().getHeaderValue());
            headers.set("X-Accel-Buffering", "no");

            long startedAt = System.nanoTime();
            AtomicReference<String> terminal = new AtomicReference<>("open");
            AtomicBoolean terminalLogged = new AtomicBoolean();
            LOGGER.info("chat_stream_start request_id={} session_id={} owner={} route=chat.stream",
                    requestId, safeId(normalized.sessionId()), owner(normalized));
            Flux<DataBuffer> buffers = Flux.defer(() ->
                            runRegistry == null ? runtime.stream(normalized) : runRegistry.start(normalized, runtime))
                    .doOnNext(event -> {
                        if ("done".equals(event.event())) {
                            terminal.set("done");
                        }
                    })
                    .map(this::encode)
                    .onErrorResume(error -> {
                        if (terminal.compareAndSet("open", "error")) {
                            LOGGER.error("chat_stream_error request_id={} session_id={} owner={} route=chat.stream",
                                    requestId, safeId(normalized.sessionId()), owner(normalized),
                                    ChatLogSupport.safeThrowable(error));
                        }
                        return Flux.just(encode(ChatSseEvents.error(errorMessage(error), normalized.sessionId())));
                    })
                    .map(payload -> response.bufferFactory().wrap(payload.getBytes(StandardCharsets.UTF_8)))
                    .doFinally(signal -> {
                        if (terminalLogged.compareAndSet(false, true)) {
                            LOGGER.info("chat_stream_terminal request_id={} session_id={} owner={} route=chat.stream terminal={} duration_ms={}",
                                    requestId, safeId(normalized.sessionId()), owner(normalized), terminal.get(),
                                    elapsedMillis(startedAt));
                        }
                    });
            return response.writeWith(buffers)
                    .doOnCancel(() -> {
                        if (terminal.compareAndSet("open", "cancel")) {
                            LOGGER.info("chat_stream_cancel request_id={} session_id={} owner={} route=chat.stream",
                                    requestId, safeId(normalized.sessionId()), owner(normalized));
                        }
                    })
                    .doOnError(error -> {
                        String kind = isDisconnect(error) ? "disconnect" : "error";
                        if (terminal.compareAndSet("open", kind)) {
                            if (isDisconnect(error)) {
                                LOGGER.info("chat_stream_disconnect request_id={} session_id={} owner={} route=chat.stream",
                                        requestId, safeId(normalized.sessionId()), owner(normalized));
                            } else {
                                LOGGER.error("chat_stream_write_error request_id={} session_id={} owner={} route=chat.stream",
                                        requestId, safeId(normalized.sessionId()), owner(normalized),
                                        ChatLogSupport.safeThrowable(error));
                            }
                        }
                    })
                    .doFinally(signal -> {
                        if (!"open".equals(terminal.get()) && terminalLogged.compareAndSet(false, true)) {
                            LOGGER.info("chat_stream_terminal request_id={} session_id={} owner={} route=chat.stream terminal={} duration_ms={}",
                                    requestId, safeId(normalized.sessionId()), owner(normalized), terminal.get(),
                                    elapsedMillis(startedAt));
                        }
                    });
        });
    }

    /**
     * 把 owner UUID 转换为不含凭据的日志字段。
     *
     * @param request 已由认证层补齐 owner 的聊天请求。
     * @return owner UUID 或 anonymous 标记。
     */
    private String owner(ChatRequest request) {
        return request.authenticatedUserId() == null ? "anonymous" : request.authenticatedUserId().toString();
    }

    /**
     * 限制会话 ID 的日志字符集，避免用户输入制造日志注入。
     *
     * @param value 待记录的会话 ID。
     * @return 可安全记录的短 ID。
     */
    private String safeId(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String safe = value.replaceAll("[^A-Za-z0-9._:-]", "_");
        return safe.substring(0, Math.min(safe.length(), 128));
    }

    /**
     * 判断 WebFlux 写响应时的常见客户端断开异常。
     *
     * @param error 写响应阶段的异常。
     * @return 异常通常由客户端提前关闭连接时为 true。
     */
    private boolean isDisconnect(Throwable error) {
        String name = error == null ? "" : error.getClass().getName();
        return name.contains("Aborted") || name.contains("PrematureClose")
                || name.contains("ClosedChannel") || name.contains("Cancellation");
    }

    /**
     * 将单调时钟起点转换为毫秒耗时。
     *
     * @param startedAt {@link System#nanoTime()} 起点。
     * @return 非负耗时毫秒。
     */
    private long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    /**
     * 为聊天 runtime 补齐 owner-scoped session ID 和认证用户 ID。
     *
     * @param request 客户端聊天请求。
     * @param exchange 当前请求上下文；兼容构造模式下不会读取它。
     * @return 完整 Java chat profile 下经过认证和会话确认的请求；依赖缺失时原样返回。
     */
    private Mono<ChatRequest> prepare(ChatRequest request, ServerWebExchange exchange) {
        if (sessionService == null || principalResolver == null) {
            return Mono.just(request);
        }
        return principalResolver.resolve(exchange)
                .flatMap(principal -> blocking(() -> {
                    String sessionId = sessionService.ensureOwned(principal.userId(), request.sessionId());
                    return request.withSessionId(sessionId).withAuthenticatedUserId(principal.userId());
                }));
    }

    /**
     * 委托 SSE 编码器生成一个文本帧。
     *
     * @param event runtime 产生的聊天事件。
     * @return 可直接写入 HTTP 响应的 SSE 文本。
     */
    private String encode(ChatSseEvent event) {
        return encoder.encode(event);
    }

    /**
     * 提取流内错误事件使用的稳定消息。
     *
     * @param error runtime 抛出的异常。
     * @return 非空异常消息；异常没有消息时返回固定的 {@code chat stream failed}。
     */
    private String errorMessage(Throwable error) {
        return ChatLogSupport.message(error);
    }

}
