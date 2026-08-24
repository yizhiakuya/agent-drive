package com.agentdrive.identity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Identity Service 的内部认证和 token introspection HTTP 契约。 */
@RestController
@RequestMapping("/internal/v1")
public final class IdentityController {
    private static final String TOKEN_HEADER = "X-Identity-Service-Token";
    private final IdentityServiceProperties properties;
    private final IdentityApplicationService service;

    /** 创建身份控制器。 */
    public IdentityController(IdentityServiceProperties properties, IdentityApplicationService service) {
        this.properties = properties;
        this.service = service;
    }

    /** 返回进程存活状态，不需要内部令牌。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "identity");
    }

    /** 返回数据库和内部令牌 readiness。 */
    @GetMapping("/ready")
    public Map<String, Object> ready(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        authorize(token);
        Map<String, Object> status = service.status();
        return Map.of("ready", true, "service", "identity", "initialized", status.get("initialized"));
    }

    /** 返回当前初始化状态，供 gateway 迁移期间探测。 */
    @GetMapping("/auth/status")
    public Map<String, Object> status(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        authorize(token);
        return service.status();
    }

    /** 首次设置 owner 密码并返回仅本次可见的 session token。 */
    @PostMapping("/auth/setup")
    public Map<String, Object> setup(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                     @Valid @RequestBody PasswordBody request) {
        authorize(token);
        return service.setup(request.password());
    }

    /** 校验 owner 密码并返回新 session token。 */
    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                     @Valid @RequestBody PasswordBody request) {
        authorize(token);
        return service.login(request.password());
    }

    /** 撤销传入的 cookie/bearer credential。 */
    @PostMapping("/auth/logout")
    public Map<String, Object> logout(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                      @RequestBody(required = false) LogoutBody request) {
        authorize(token);
        return service.logout(request == null ? null : request.cookie(),
                request == null ? null : request.bearer());
    }

    /** 验证一个来自 gateway 的 session/device credential。 */
    @PostMapping("/introspect")
    public Map<String, Object> introspect(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                          @Valid @RequestBody CredentialBody request) {
        authorize(token);
        return service.introspect(request.credential());
    }

    /** 注册过渡期 API 已生成的 credential hash。 */
    @PostMapping("/credentials/register")
    public Map<String, Object> registerCredential(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                                   @Valid @RequestBody RegisterBody request) {
        authorize(token);
        return service.registerCredential(new IdentityApplicationService.RegisterRequest(
                request.ownerId(), request.kind(), request.tokenHash(), request.expiresAt()));
    }

    private void authorize(String token) {
        if (properties.internalToken().isBlank() || token == null || !MessageDigest.isEqual(
                properties.internalToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "identity service token is invalid");
        }
    }

    /** 密码 JSON 请求体。 */
    public record PasswordBody(@JsonProperty("password") @NotBlank @Size(max = 128) String password) {
    }

    /** logout JSON 请求体；两个 credential 均可为空。 */
    public record LogoutBody(@JsonProperty("cookie") String cookie, @JsonProperty("bearer") String bearer) {
    }

    /** introspection JSON 请求体。 */
    public record CredentialBody(@JsonProperty("credential") @NotBlank @Size(max = 512) String credential) {
    }

    /** 仅内部迁移/双写使用的 credential hash 请求体。 */
    public record RegisterBody(
            @JsonProperty("owner_id") @NotBlank @Size(max = 64) String ownerId,
            @JsonProperty("kind") @NotBlank @Size(max = 16) String kind,
            @JsonProperty("token_hash") @NotBlank @Size(max = 128) String tokenHash,
            @JsonProperty("expires_at") java.time.Instant expiresAt
    ) {
    }

    /** 返回稳定身份业务错误 envelope。 */
    @ExceptionHandler(IdentityApplicationService.IdentityException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> identityError(
            IdentityApplicationService.IdentityException error) {
        return org.springframework.http.ResponseEntity.status(error.status())
                .body(Map.of("ok", false, "status", error.status(), "code", error.code(),
                        "detail", safeDetail(error)));
    }

    private String safeDetail(Exception error) {
        String detail = error.getMessage();
        return detail == null || detail.isBlank() ? "identity request failed" : detail;
    }
}
