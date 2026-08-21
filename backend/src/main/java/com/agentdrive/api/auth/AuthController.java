package com.agentdrive.api.auth;

import com.agentdrive.auth.AuthenticatedPrincipal;
import com.agentdrive.auth.AuthRateLimiter;
import com.agentdrive.auth.AuthService;
import com.agentdrive.infrastructure.AppProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * 暴露密码会话、设备令牌和扫码配对相关的认证端点。
 *
 * <p>密码操作和配对交换按客户端地址限速，并把阻塞式 {@link AuthService} 调用移到
 * bounded-elastic 调度器。登录/初始化成功后同时返回会话令牌并写入 HttpOnly Cookie；
 * 设备令牌只通过 JSON 返回，不写入 Cookie。控制器本身不保存认证状态，状态由
 * {@code AuthService} 持久化管理。
 */
@RestController
@Profile({"java-auth", "java-chat"})
@RequestMapping("/api/v1/auth")
public final class AuthController {
    public static final String SESSION_COOKIE = "agentdrive_session";

    private final AuthService auth;
    private final AppProperties properties;
    private final WebRequestPrincipalResolver principalResolver;
    private final AuthRateLimiter rateLimiter;

    /**
     * 创建使用默认内存限速器的认证控制器。
     *
     * @param auth 负责初始化密码、会话、设备令牌和配对状态的认证服务。
     * @param properties 提供 Cookie 的 secure 等部署配置。
     * @param principalResolver 将当前请求凭据解析为用户主体。
     */
    public AuthController(AuthService auth,
                          AppProperties properties,
                          WebRequestPrincipalResolver principalResolver) {
        this(auth, properties, principalResolver, new AuthRateLimiter());
    }

    /**
     * 创建认证控制器并保存其请求边界依赖。
     *
     * @param auth 执行认证和令牌状态变更的服务。
     * @param properties 提供会话 Cookie 的安全属性配置。
     * @param principalResolver 校验 Cookie/Bearer 凭据并解析用户主体。
     * @param rateLimiter 按操作名和客户端地址限制 setup、login、pair-exchange 请求。
     */
    @Autowired
    public AuthController(AuthService auth,
                          AppProperties properties,
                          WebRequestPrincipalResolver principalResolver,
                          AuthRateLimiter rateLimiter) {
        this.auth = auth;
        this.properties = properties;
        this.principalResolver = principalResolver;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 响应 {@code GET /api/v1/auth/status}，报告密码认证是否已初始化。
     *
     * @return 在阻塞查询完成后发出 {@code initialized} 布尔字段的响应。
     */
    @GetMapping("/status")
    public Mono<Map<String, Boolean>> status() {
        return blocking(auth::initialized).map(initialized -> Map.of("initialized", initialized));
    }

    /**
     * 响应 {@code POST /api/v1/auth/setup}，设置首次密码并建立会话。
     *
     * @param payload 包含 1 至 128 个字符密码的请求体。
     * @param response 用于写入新会话 Cookie 的响应。
     * @param exchange 用于按客户端地址执行 setup 限速的请求上下文。
     * @return 认证服务创建的会话令牌及 {@code ok=true}。
     */
    @PostMapping("/setup")
    public Mono<AuthSessionResponse> setup(@Valid @RequestBody PasswordRequest payload,
                                           ServerHttpResponse response,
                                           ServerWebExchange exchange) {
        requireRate("setup", exchange, AuthRateLimiter.DEFAULT_LIMIT);
        return blocking(() -> auth.setup(payload.password()))
                .map(result -> {
                    addSessionCookie(response, result.sessionToken());
                    return new AuthSessionResponse(true, result.sessionToken());
                });
    }

    /**
     * 响应 {@code POST /api/v1/auth/login}，校验密码并建立会话。
     *
     * @param payload 待校验的密码请求体。
     * @param response 用于写入新会话 Cookie 的响应。
     * @param exchange 用于按客户端地址执行 login 限速的请求上下文。
     * @return 认证服务创建的会话令牌及 {@code ok=true}。
     */
    @PostMapping("/login")
    public Mono<AuthSessionResponse> login(@Valid @RequestBody PasswordRequest payload,
                                           ServerHttpResponse response,
                                           ServerWebExchange exchange) {
        requireRate("login", exchange, AuthRateLimiter.DEFAULT_LIMIT);
        return blocking(() -> auth.login(payload.password()))
                .map(result -> {
                    addSessionCookie(response, result.sessionToken());
                    return new AuthSessionResponse(true, result.sessionToken());
                });
    }

    /**
     * 响应 {@code POST /api/v1/auth/logout}，撤销请求中的 Cookie/Bearer 凭据并过期 Cookie。
     *
     * @param exchange 提供会话 Cookie、Authorization Bearer 令牌和请求上下文。
     * @param response 用于发送立即过期的会话 Cookie。
     * @return 认证服务处理完成后发出 {@code ok=true}。
     */
    @PostMapping("/logout")
    public Mono<Map<String, Boolean>> logout(ServerWebExchange exchange,
                                             ServerHttpResponse response) {
        String cookie = Optional.ofNullable(exchange.getRequest().getCookies().getFirst(SESSION_COOKIE))
                .map(cookieValue -> cookieValue.getValue())
                .orElse(null);
        String bearer = bearer(exchange);
        return blocking(() -> auth.logout(cookie, bearer))
                .map(ignored -> {
                    response.addCookie(expiredSessionCookie());
                    return Map.of("ok", true);
                });
    }

    /**
     * 响应 {@code GET /api/v1/auth/me}，仅在当前请求凭据通过解析后返回成功。
     *
     * @param exchange 包含 Cookie 或 Bearer 凭据的请求上下文。
     * @return 认证成功时发出 {@code authed=true}；凭据缺失或无效时由解析器返回 401。
     */
    @GetMapping("/me")
    public Mono<Map<String, Boolean>> me(ServerWebExchange exchange) {
        return principalResolver.resolve(exchange).map(ignored -> Map.of("authed", true));
    }

    /**
     * 响应 {@code POST /api/v1/auth/device-token}，为已认证用户签发设备令牌。
     *
     * @param payload 指定客户端 {@code device_id}，以及可选设备名称的请求体。
     * @param exchange 用于解析签发者用户主体的请求上下文。
     * @return 包含明文设备令牌和设备 ID 的响应；令牌只在此响应中返回。
     */
    @PostMapping("/device-token")
    public Mono<Map<String, String>> deviceToken(@Valid @RequestBody DeviceTokenRequest payload,
                                                 ServerWebExchange exchange) {
        return principalResolver.resolve(exchange)
                .flatMap(principal -> blocking(() -> auth.issueDeviceToken(
                        principal.userId(), payload.deviceId(), payload.name()
                )))
                .map(result -> Map.of("token", result.token(), "device_id", result.deviceId()));
    }

    /**
     * 响应 {@code POST /api/v1/auth/pairing}，为当前 Web 会话生成一次性配对码。
     *
     * @param exchange 必须包含会话凭据的请求上下文；设备令牌不能生成配对码。
     * @return 配对码及其剩余有效秒数；配对码由认证服务持久化并受未使用数量上限约束。
     */
    @PostMapping("/pairing")
    public Mono<Map<String, Object>> pairing(ServerWebExchange exchange) {
        return principalResolver.resolve(exchange)
                .flatMap(principal -> {
                    if (principal.credentialKind() != AuthenticatedPrincipal.CredentialKind.SESSION) {
                        return Mono.error(new AuthService.InvalidCredentialException(
                                "web session is required to issue a pairing code"
                        ));
                    }
                    return blocking(() -> auth.issuePairing(principal.userId()));
                })
                .map(result -> {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("code", result.code());
                    response.put("expires_in", result.expiresIn());
                    return response;
                });
    }

    /**
     * 响应 {@code POST /api/v1/auth/pair-exchange}，消费配对码并签发设备令牌。
     *
     * @param payload 包含配对码、客户端 {@code device_id} 和可选设备名称的请求体。
     * @param exchange 用于按客户端地址执行配对交换限速的请求上下文。
     * @return 新设备令牌及其设备 ID；无效、过期或已消费的配对码由认证服务报错。
     */
    @PostMapping("/pair-exchange")
    public Mono<Map<String, String>> pairExchange(@Valid @RequestBody PairExchangeRequest payload,
                                                  ServerWebExchange exchange) {
        requireRate("pair", exchange, AuthRateLimiter.PAIRING_EXCHANGE_LIMIT);
        return blocking(() -> auth.exchangePairing(
                        payload.code(), payload.deviceId(), payload.name()
                ))
                .map(result -> Map.of("token", result.token(), "device_id", result.deviceId()));
    }

    /**
     * 按操作名和远端 IP 执行认证接口限速。
     *
     * @param operation 限速桶名称，例如 {@code setup}、{@code login} 或 {@code pair}。
     * @param exchange 用于提取远端 IP；无法取得地址时使用 {@code unknown} 桶。
     * @param limit 该桶允许的尝试次数。
     * @throws AuthService.RateLimitExceededException 超过窗口限制时抛出，交由异常处理器返回 429。
     */
    private void requireRate(String operation, ServerWebExchange exchange, int limit) {
        String address = clientAddress(exchange.getRequest().getRemoteAddress(),
                exchange.getRequest().getHeaders().getFirst("X-Forwarded-For"));
        if (!rateLimiter.allow(operation + ":" + address, limit)) {
            throw new AuthService.RateLimitExceededException("too many attempts, try again later");
        }
    }

    /**
     * 解析认证限速使用的客户端地址。仅当 TCP 对端是回环地址时才信任 nginx 写入的单个
     * {@code X-Forwarded-For} IP；直连请求或伪造的多段/非 IP 头始终退回真实对端。
     * @param remoteAddress TCP 连接的真实对端地址。
     * @param forwardedFor nginx 覆写后的单个客户端 IP；直连请求可能为空。
     * @return 用于限速键的可信客户端 IP，无法解析时返回真实对端或 {@code unknown}。
     */
    static String clientAddress(InetSocketAddress remoteAddress, String forwardedFor) {
        InetAddress remote = remoteAddress == null ? null : remoteAddress.getAddress();
        String fallback = remote == null ? "unknown" : remote.getHostAddress();
        if (remote == null || !remote.isLoopbackAddress()) return fallback;
        String candidate = forwardedFor == null ? "" : forwardedFor.trim();
        if (candidate.isEmpty() || candidate.indexOf(',') >= 0 || !isIpLiteral(candidate)) return fallback;
        return candidate;
    }

    /**
     * 判断值是否为不触发 DNS 解析的 IPv4 或 IPv6 字面量。
     * @param value 待校验的单个地址文本。
     * @return 地址是合法 IP 字面量时为 true。
     */
    private static boolean isIpLiteral(String value) {
        if (value.indexOf(':') >= 0) {
            if (!value.matches("[0-9A-Fa-f:.]+")) return false;
            try {
                return InetAddress.getByName(value) instanceof Inet6Address;
            } catch (UnknownHostException ignored) {
                return false;
            }
        }
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3 || !octet.chars().allMatch(Character::isDigit)) return false;
            if (Integer.parseInt(octet) > 255) return false;
        }
        return true;
    }

    /**
     * 从 Authorization 头提取 Bearer 凭据。
     *
     * @param exchange 当前 Web 请求上下文。
     * @return 去掉 {@code Bearer} 前缀并裁剪空白后的令牌；头部缺失、前缀不匹配或令牌为空时返回 {@code null}。
     */
    private String bearer(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String value = authorization.substring(7).trim();
        return value.isBlank() ? null : value;
    }

    /**
     * 将会话令牌写入响应 Cookie。
     *
     * @param response 待追加 Cookie 的响应对象。
     * @param token 认证服务创建的会话令牌；不会在此方法中持久化或改写。
     */
    private void addSessionCookie(ServerHttpResponse response, String token) {
        response.addCookie(ResponseCookie.from(SESSION_COOKIE, token)
                .httpOnly(true)
                .secure(properties.secureCookies())
                .sameSite("Lax")
                .path("/")
                .maxAge(AuthService.SESSION_TTL)
                .build());
    }

    /**
     * 创建用于清除浏览器会话 Cookie 的响应 Cookie。
     *
     * @return 与登录 Cookie 同名、路径和安全属性一致但 {@code maxAge=0} 的 Cookie。
     */
    private ResponseCookie expiredSessionCookie() {
        return ResponseCookie.from(SESSION_COOKIE, "")
                .httpOnly(true)
                .secure(properties.secureCookies())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    /**
     * 把认证服务的同步调用包装成 Reactor 异步操作。
     *
     * @param operation 待读取或写入认证状态的阻塞调用。
     * @param <T> 调用结果类型。
     * @return 在 bounded-elastic 调度器执行该调用的 {@link Mono}。
     */
    private <T> Mono<T> blocking(Callable<T> operation) {
        return Mono.fromCallable(operation).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * setup/login 请求体；密码由 Bean Validation 限制为非空且不超过 128 个字符。
     */
    public record PasswordRequest(
            @NotBlank @Size(min = 1, max = 128) String password
    ) {
    }

    /**
     * 设备令牌签发请求体。
     *
     * <p>JSON 使用客户端契约中的 {@code device_id} 字段；名称仅用于设备展示。
     */
    public record DeviceTokenRequest(
            @JsonProperty("device_id") @NotBlank @Size(max = 128) String deviceId,
            @Size(max = 128) String name
    ) {
    }

    /**
     * 扫码配对交换请求体，携带一次性配对码和待注册设备信息。
     */
    public record PairExchangeRequest(
            @NotBlank @Size(min = 8, max = 128) String code,
            @JsonProperty("device_id") @NotBlank @Size(max = 128) String deviceId,
            @Size(max = 128) String name
    ) {
    }

    /**
     * setup/login 成功后的会话响应；{@code session} 是供客户端后续请求使用的令牌。
     */
    public record AuthSessionResponse(boolean ok, String session) {
    }
}
