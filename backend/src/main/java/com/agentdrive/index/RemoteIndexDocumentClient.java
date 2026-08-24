package com.agentdrive.index;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Index Service 文档迁移客户端。
 *
 * <p>当前只用于迁移/双读校验，不替换 API 内的 {@link IndexStore}。请求带 owner、
 * file 和 source revision，远程服务不会接触主 API 的数据库或本地路径。</p>
 */
public final class RemoteIndexDocumentClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String TOKEN_HEADER = "X-Index-Service-Token";
    private final URI baseUrl;
    private final String token;
    private final ObjectMapper objectMapper;
    private final HttpClient client = HttpClientSupport.builder(TIMEOUT).build();

    /** 创建 Index Service 迁移客户端。 */
    public RemoteIndexDocumentClient(String serviceUrl, String token, ObjectMapper objectMapper) {
        this.baseUrl = endpoint(serviceUrl, "");
        this.token = token == null ? "" : token.trim();
        this.objectMapper = objectMapper;
    }

    /** 验证远程 Index Service readiness。 */
    public void requireReady() {
        try {
            HttpResponse<String> response = client.send(request("/internal/v1/ready", "GET", null),
                    HttpClientSupport.limitedUtf8BodyHandler(32 * 1024));
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || !objectMapper.readTree(response.body()).path("ready").asBoolean(false)) {
                throw new IllegalStateException("index service is not ready");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("index service readiness interrupted", error);
        } catch (IOException error) {
            throw new IllegalStateException("index service readiness failed", error);
        }
    }

    /** 拉取 owner 文档迁移清单。 */
    public Map<String, Object> manifest(UUID ownerId) {
        return get("/internal/v1/index/manifest?owner_id=" + ownerId);
    }

    /** 原子替换一个 owner 文件 revision 的正文和 chunks。 */
    public Map<String, Object> replace(UUID ownerId, UUID fileId, long revision,
                                       String documentType, String extractorVersion,
                                       String content, String chunkVersion, List<String> chunks) {
        Map<String, Object> body = Map.of(
                "owner_id", ownerId.toString(),
                "file_id", fileId.toString(),
                "source_revision", revision,
                "document_type", documentType,
                "extractor_version", extractorVersion,
                "content", content == null ? "" : content,
                "chunk_version", chunkVersion,
                "chunks", chunks == null ? List.of() : chunks);
        return post("/internal/v1/index/documents", body);
    }

    private Map<String, Object> get(String path) {
        try {
            HttpResponse<String> response = client.send(request(path, "GET", null),
                    HttpClientSupport.limitedUtf8BodyHandler(4 * 1024 * 1024));
            return parse(response);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("index service request interrupted", error);
        } catch (IOException error) {
            throw new IllegalStateException("index service request failed", error);
        }
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        try {
            HttpResponse<String> response = client.send(request(path, "POST",
                            objectMapper.writeValueAsString(body)),
                    HttpClientSupport.limitedUtf8BodyHandler(4 * 1024 * 1024));
            return parse(response);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("index service request interrupted", error);
        } catch (IOException error) {
            throw new IllegalStateException("index service request failed", error);
        }
    }

    private Map<String, Object> parse(HttpResponse<String> response) throws IOException {
        JsonNode root = objectMapper.readTree(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300
                || root == null || Boolean.FALSE.equals(root.path("ok").isMissingNode()
                ? null : root.path("ok").asBoolean())) {
            throw new IllegalStateException("index service returned HTTP " + response.statusCode());
        }
        return objectMapper.convertValue(root, new com.fasterxml.jackson.core.type.TypeReference<>() { });
    }

    private HttpRequest request(String path, String method, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(baseUrl.toString(), path))
                .timeout(TIMEOUT)
                .header(TOKEN_HEADER, token)
                .header("Accept", "application/json");
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        return builder.build();
    }

    private URI endpoint(String raw, String suffix) {
        try {
            URI base = new URI(raw == null ? "" : raw.trim().replaceAll("/+$", ""));
            String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || base.getHost() == null
                    || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException("index service URL is invalid");
            }
            return suffix.isEmpty() ? base : URI.create(base + suffix);
        } catch (Exception error) {
            throw new IllegalArgumentException("index service URL is invalid", error);
        }
    }
}
