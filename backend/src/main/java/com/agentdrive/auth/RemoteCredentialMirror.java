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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 通过 Identity Service 内部 API 双写/撤销 credential 哈希。 */
public final class RemoteCredentialMirror implements CredentialMirror {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String TOKEN_HEADER = "X-Identity-Service-Token";
    private final URI registerEndpoint;
    private final URI revokeEndpoint;
    private final String token;
    private final ObjectMapper objectMapper;
    private final HttpClient client = HttpClientSupport.builder(TIMEOUT).build();

    /** 创建远程 credential 双写客户端。 */
    public RemoteCredentialMirror(String serviceUrl, String token, ObjectMapper objectMapper) {
        this.registerEndpoint = endpoint(serviceUrl, "/internal/v1/credentials/register");
        this.revokeEndpoint = endpoint(serviceUrl, "/internal/v1/credentials/revoke");
        this.token = token == null ? "" : token.trim();
        this.objectMapper = objectMapper;
    }

    /** 注册 credential hash。 */
    @Override
    public void register(UUID ownerId, AuthenticatedPrincipal.CredentialKind kind,
                          String tokenHash, Instant expiresAt) {
        post(registerEndpoint, Map.of("owner_id", ownerId.toString(), "kind", kind.name(),
                "token_hash", tokenHash, "expires_at", expiresAt.toString()));
    }

    /** 撤销 credential hash。 */
    @Override
    public void revoke(String tokenHash) {
        post(revokeEndpoint, Map.of("token_hash", tokenHash));
    }

    private void post(URI endpoint, Map<String, Object> body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(TIMEOUT)
                    .header(TOKEN_HEADER, token)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body),
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpClientSupport.limitedUtf8BodyHandler(32 * 1024));
            JsonNode root = objectMapper.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || root == null || !root.path("ok").asBoolean(false)) {
                throw new IllegalStateException("identity credential mirror returned HTTP " + response.statusCode());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("identity credential mirror interrupted", error);
        } catch (IOException error) {
            throw new IllegalStateException("identity credential mirror failed", error);
        }
    }

    private URI endpoint(String raw, String suffix) {
        try {
            URI base = new URI(raw == null ? "" : raw.trim().replaceAll("/+$", ""));
            String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || base.getHost() == null
                    || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException("identity service URL is invalid");
            }
            String path = base.getPath() == null ? "" : base.getPath();
            return path.endsWith(suffix) ? base : URI.create(base + suffix);
        } catch (Exception error) {
            throw new IllegalArgumentException("identity service URL is invalid", error);
        }
    }
}
