package com.agentdrive.api.auth;

import com.agentdrive.auth.AuthenticatedPrincipal;
import com.agentdrive.auth.CredentialAuthenticator;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 将 Web 请求中的认证凭据解析为已认证用户主体。
 *
 * <p>普通 API 只接受 {@code agentdrive_session} Cookie 或 Authorization Bearer
 * 令牌；媒体端点在两者缺失时额外兼容查询参数 {@code token}。凭据校验通过
 * {@link CredentialAuthenticator} 完成，并始终放到 bounded-elastic 调度器，避免
 * 同步数据库校验阻塞 WebFlux 事件循环。
 */
@Component
public final class WebRequestPrincipalResolver {
    private static final String SESSION_COOKIE = "agentdrive_session";

    private final CredentialAuthenticator authenticator;

    /**
     * 创建凭据解析器。
     *
     * @param authenticator 校验会话和设备令牌并返回用户主体的认证器。
     */
    public WebRequestPrincipalResolver(CredentialAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    /**
     * 解析普通 API 请求中的 Cookie 或 Bearer 凭据。
     *
     * @param exchange 当前 Web 请求上下文。
     * @return 在认证完成后发出用户主体的 {@link Mono}。
     * @throws ResponseStatusException 请求没有任何凭据，或凭据校验失败时产生 401。
     */
    public Mono<AuthenticatedPrincipal> resolve(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        String cookie = cookie(request);
        String bearer = bearer(request);
        if (cookie == null && bearer == null) {
            return Mono.error(unauthorized());
        }
        return Mono.fromCallable(() -> authenticate(cookie, bearer))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 解析文件媒体请求的凭据。
     *
     * <p>优先使用普通 Cookie/Bearer 认证；只有两者都缺失时才读取查询参数
     * {@code token}，以兼容浏览器直接打开下载或预览 URL 的场景。
     *
     * @param exchange 当前 Web 请求上下文。
     * @return 在认证完成后发出用户主体的 {@link Mono}。
     * @throws ResponseStatusException 没有可用凭据或查询令牌无效时产生 401。
     */
    public Mono<AuthenticatedPrincipal> resolveMedia(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        if (cookie(request) != null || bearer(request) != null) {
            return resolve(exchange);
        }
        String token = request.getQueryParams().getFirst("token");
        if (token == null || token.isBlank()) {
            return Mono.error(unauthorized());
        }
        return Mono.fromCallable(() -> authenticator.authenticate(token).orElseThrow(this::unauthorized))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 按 Cookie 优先、Bearer 次之的顺序验证凭据。
     *
     * @param cookie 会话 Cookie 值，可为 {@code null}。
     * @param bearer Authorization Bearer 值，可为 {@code null}。
     * @return 第一个通过认证的用户主体。
     * @throws ResponseStatusException 两种凭据都不存在或都未通过认证时产生 401。
     */
    private AuthenticatedPrincipal authenticate(String cookie, String bearer) {
        if (cookie != null) {
            var principal = authenticator.authenticate(cookie);
            if (principal.isPresent()) {
                return principal.get();
            }
        }
        if (bearer != null) {
            var principal = authenticator.authenticate(bearer);
            if (principal.isPresent()) {
                return principal.get();
            }
        }
        throw unauthorized();
    }

    /**
     * 读取会话 Cookie，并过滤缺失或空值。
     *
     * @param request 当前 HTTP 请求。
     * @return {@code agentdrive_session} 的非空值；不存在时返回 {@code null}。
     */
    private String cookie(ServerHttpRequest request) {
        var cookie = request.getCookies().getFirst(SESSION_COOKIE);
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            return null;
        }
        return cookie.getValue();
    }

    /**
     * 读取 Authorization 头中的 Bearer 令牌。
     *
     * @param request 当前 HTTP 请求。
     * @return 去除前缀和首尾空白的令牌；格式不符或值为空时返回 {@code null}。
     */
    private String bearer(ServerHttpRequest request) {
        String authorization = request.getHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String value = authorization.substring("Bearer ".length()).trim();
        return value.isBlank() ? null : value;
    }

    /**
     * 创建统一的未认证异常。
     *
     * @return 状态码为 401、detail 为 {@code authentication required} 的异常。
     */
    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
}
