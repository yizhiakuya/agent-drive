package com.agentdrive.api.sessions;

import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.auth.ConversationSessionService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提供当前用户对聊天会话的列表、详情、摘要和删除 API。
 *
 * <p>所有查询和变更都先由 {@link WebRequestPrincipalResolver} 解析 owner，再把
 * 同步的会话服务调用移到 bounded-elastic。会话服务负责按 owner 隔离数据，并在
 * 摘要和删除操作中持久化相应的标题或终态。
 */
@RestController
@Profile({"java-auth", "java-chat"})
@RequestMapping("/api/v1/sessions")
public final class SessionController {
    private final ConversationSessionService sessions;
    private final WebRequestPrincipalResolver principalResolver;

    /**
     * 创建会话 API 控制器。
     *
     * @param sessions 读取、总结和删除 owner-scoped 会话的服务。
     * @param principalResolver 将请求凭据解析为会话 owner。
     */
    public SessionController(ConversationSessionService sessions,
                              WebRequestPrincipalResolver principalResolver) {
        this.sessions = sessions;
        this.principalResolver = principalResolver;
    }

    /**
     * 响应 {@code GET /api/v1/sessions}，列出当前用户的会话摘要。
     *
     * @param exchange 用于解析 owner 的请求上下文。
     * @return 包含 {@code sessions} 列表的异步 JSON 响应。
     */
    @GetMapping
    public Mono<Map<String, Object>> list(ServerWebExchange exchange) {
        return principalResolver.resolve(exchange)
                .flatMap(principal -> blocking(() -> Map.of(
                        "sessions", sessions.listOwned(principal.userId())
                )));
    }

    /**
     * 响应 {@code GET /api/v1/sessions/{sessionId} }，返回会话元数据和消息。
     *
     * @param sessionId 客户端会话标识。
     * @param exchange 用于限制查询到当前用户的请求上下文。
     * @return 含 {@code meta} 和 {@code messages} 字段的会话详情；不存在或不属于当前 owner 时由服务抛出 404 语义异常。
     */
    @GetMapping("/{sessionId}")
    public Mono<Map<String, Object>> get(@PathVariable String sessionId,
                                         ServerWebExchange exchange) {
        return principalResolver.resolve(exchange)
                .flatMap(principal -> blocking(() -> {
                    ConversationSessionService.SessionDetails details =
                            sessions.getOwned(principal.userId(), sessionId);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("meta", details.meta());
                    result.put("messages", details.messages());
                    return result;
                }));
    }

    /**
     * 响应 {@code POST /api/v1/sessions/{sessionId}/summarize}，为会话生成或刷新标题摘要。
     *
     * @param sessionId 待总结的会话标识。
     * @param exchange 用于限制总结到当前用户会话的请求上下文。
     * @return 会话服务生成并持久化的摘要结果。
     */
    @PostMapping("/{sessionId}/summarize")
    public Mono<Map<String, Object>> summarize(@PathVariable String sessionId,
                                                ServerWebExchange exchange) {
        return principalResolver.resolve(exchange)
                .flatMap(principal -> blocking(() -> sessions.summarizeOwned(principal.userId(), sessionId)));
    }

    /**
     * 响应 {@code DELETE /api/v1/sessions/{sessionId}}，删除当前用户的会话及其消息。
     *
     * @param sessionId 待删除的会话标识。
     * @param exchange 用于限制删除到当前用户的请求上下文。
     * @return 删除完成后发出被删除会话 ID。
     */
    @DeleteMapping("/{sessionId}")
    public Mono<Map<String, String>> delete(@PathVariable String sessionId,
                                             ServerWebExchange exchange) {
        return principalResolver.resolve(exchange)
                .flatMap(principal -> blocking(() -> {
                    sessions.deleteOwned(principal.userId(), sessionId);
                    return Map.of("deleted", sessionId);
                }));
    }

    /**
     * 将会话服务的同步持久化调用移出 WebFlux 事件循环。
     *
     * @param operation 要执行的会话查询或变更。
     * @param <T> 操作结果类型。
     * @return 在 bounded-elastic 调度器运行操作的异步结果。
     */
    private <T> Mono<T> blocking(java.util.concurrent.Callable<T> operation) {
        return Mono.fromCallable(operation).subscribeOn(Schedulers.boundedElastic());
    }
}
