package com.agentdrive.auth;

import com.agentdrive.net.HttpClientSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 通过 Identity Service introspection 校验 owner credential。
 *
 * <p>该适配器只在显式配置 Identity Service URL/token 时使用；默认仍由当前 API 的
 * PostgreSQL 适配器校验。原始 credential 只存在于本次内网请求，不写入日志或结果。</p>
 */
public final class RemoteCredentialAuthenticator implements CredentialAuthenticator {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String TOKEN_HEADER = "X-Identity-Service-Token";
    private final URI endpoint;
    private final String token;
    private final ObjectMapper objectMapper;
    private final HttpClient client = HttpClientSupport.builder(TIMEOUT).build();

    /** 创建远程身份 introspection 客户端。 */
    public RemoteCredentialAuthenticator(String serviceUrl, String token, ObjectMapper objectMapper) {
        this.endpoint = endpoint(serviceUrl);
        this.token = token == null ? "" : token.trim();
        this.objectMapper = objectMapper;
    }

    /** 请求 Identity Service 并转换为最小认证 principal。 */
    @Override
    public Optional<AuthenticatedPrincipal> authenticate(String credential) {
        if (credential == null || credential.isBlank() || token.isBlank()) return Optional.empty();
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(TIMEOUT)
                    .header(TOKEN_HEADER, token)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(Map.of("credential", credential)),
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpClientSupport.limitedUtf8BodyHandler(32 * 1024));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("identity service returned HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (root == null || !root.path("authenticated").asBoolean(false)) return Optional.empty();
            UUID owner = UUID.fromString(root.path("owner_id").asText(""));
            AuthenticatedPrincipal.CredentialKind kind = switch (root.path("kind").asText("")) {
                case "SESSION" -> AuthenticatedPrincipal.CredentialKind.SESSION;
                case "DEVICE" -> AuthenticatedPrincipal.CredentialKind.DEVICE;
                default -> throw new IllegalStateException("identity service returned invalid credential kind");
            };
            return Optional.of(new AuthenticatedPrincipal(owner, kind));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("identity service request interrupted", error);
        } catch (IOException | RuntimeException error) {
            if (error instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("identity service request failed", error);
        }
    }

    private URI endpoint(String raw) {
        try {
            URI base = new URI(raw == null ? "" : raw.trim().replaceAll("/+$", ""));
            String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || base.getHost() == null
                    || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException("identity service URL is invalid");
            }
            String path = base.getPath() == null ? "" : base.getPath();
            return path.endsWith("/internal/v1/introspect")
                    ? base : URI.create(base + "/internal/v1/introspect");
        } catch (Exception error) {
            throw new IllegalArgumentException("identity service URL is invalid", error);
        }
    }
}
