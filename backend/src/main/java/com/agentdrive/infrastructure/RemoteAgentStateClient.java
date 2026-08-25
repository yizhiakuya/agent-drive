package com.agentdrive.infrastructure;

import com.agentdrive.net.HttpClientSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Agent Service 内部状态 HTTP 客户端；不向模型暴露 URL/token。 */
public final class RemoteAgentStateClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String TOKEN_HEADER = "X-Agent-Service-Token";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private final URI endpoint;
    private final String token;
    private final ObjectMapper objectMapper;
    private final HttpClient client = HttpClientSupport.builder(TIMEOUT).build();

    public RemoteAgentStateClient(String serviceUrl, String token, ObjectMapper objectMapper) {
        this.endpoint = endpoint(serviceUrl);
        this.token = token == null ? "" : token.trim();
        this.objectMapper = objectMapper;
    }

    public void requireReady() {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("/internal/v1/ready"))
                    .timeout(TIMEOUT).header(TOKEN_HEADER, token).header("Accept", "application/json").GET().build();
            HttpResponse<String> response = client.send(request, HttpClientSupport.limitedUtf8BodyHandler(32 * 1024));
            JsonNode root = objectMapper.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || root == null || !root.path("ready").asBoolean(false)) {
                throw new IllegalStateException("agent service is not ready");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("agent service readiness interrupted", error);
        } catch (IOException error) {
            throw new IllegalStateException("agent service readiness failed", error);
        }
    }

    public Map<String, Object> call(String action, Map<String, Object> values) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        if (values != null) body.putAll(values);
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("/internal/v1/chat/state"))
                    .timeout(TIMEOUT).header(TOKEN_HEADER, token)
                    .header("Accept", "application/json").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = client.send(request,
                    HttpClientSupport.limitedUtf8BodyHandler(16 * 1024 * 1024));
            JsonNode root = objectMapper.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || root == null || !root.path("ok").asBoolean(false)) {
                String detail = root == null ? "agent service request failed"
                        : root.path("detail").asText(root.path("error").asText("agent service request failed"));
                throw new IllegalStateException(detail);
            }
            JsonNode result = root.path("result");
            if (result.isMissingNode() || result.isNull()) return Map.of();
            return objectMapper.convertValue(result, MAP);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("agent service request interrupted", error);
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("agent service request failed", error);
        }
    }

    private URI endpoint(String raw) {
        try {
            URI base = new URI(raw == null ? "" : raw.trim().replaceAll("/+$", ""));
            String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || base.getHost() == null
                    || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException("agent service URL is invalid");
            }
            return base;
        } catch (Exception error) {
            throw new IllegalArgumentException("agent service URL is invalid", error);
        }
    }
}
