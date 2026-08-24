package com.agentdrive.files;

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
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/** 通过 File Service 内部镜像契约同步单文件内容。 */
public final class RemoteFileMirror implements FileMirrorPort {
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final String TOKEN_HEADER = "X-File-Service-Token";
    private final URI serviceRoot;
    private final URI mirrorEndpoint;
    private final URI deleteEndpoint;
    private final String token;
    private final ObjectMapper objectMapper;
    private final HttpClient client = HttpClientSupport.builder(TIMEOUT).build();

    /** 创建远程文件镜像客户端。 */
    public RemoteFileMirror(String serviceUrl, String token, ObjectMapper objectMapper) {
        this.serviceRoot = endpoint(serviceUrl, "");
        this.mirrorEndpoint = endpoint(serviceUrl, "/internal/v1/files/mirror");
        this.deleteEndpoint = this.mirrorEndpoint;
        this.token = token == null ? "" : token.trim();
        this.objectMapper = objectMapper;
    }

    /** 发送原始 bytes 并要求远程服务重新验证 MD5。 */
    @Override
    public void syncFile(UUID ownerId, String path, long revision, byte[] bytes, String contentMd5) {
        if (ownerId == null || path == null || bytes == null || contentMd5 == null || contentMd5.isBlank()) {
            throw new FileStorageException(400, "文件镜像参数无效");
        }
        try {
            Map<String, Object> body = Map.of(
                    "owner_id", ownerId.toString(),
                    "path", path,
                    "revision", revision,
                    "content_md5", contentMd5,
                    "data", Base64.getEncoder().encodeToString(bytes));
            HttpRequest request = HttpRequest.newBuilder(mirrorEndpoint)
                    .timeout(TIMEOUT)
                    .header(TOKEN_HEADER, token)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body),
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpClientSupport.limitedUtf8BodyHandler(64 * 1024));
            requireOk(response);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new FileStorageException(502, "文件镜像请求中断", error);
        } catch (IOException error) {
            throw new FileStorageException(502, "文件镜像请求失败", error);
        }
    }

    /** 删除远程文件镜像。 */
    @Override
    public void deleteFile(UUID ownerId, String path) {
        try {
            String query = "?owner_id=" + java.net.URLEncoder.encode(ownerId.toString(), StandardCharsets.UTF_8)
                    + "&path=" + java.net.URLEncoder.encode(path, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(deleteEndpoint + query))
                    .timeout(TIMEOUT)
                    .header(TOKEN_HEADER, token)
                    .header("Accept", "application/json")
                    .DELETE()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpClientSupport.limitedUtf8BodyHandler(64 * 1024));
            requireOk(response);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new FileStorageException(502, "文件镜像删除中断", error);
        } catch (IOException error) {
            throw new FileStorageException(502, "文件镜像删除失败", error);
        }
    }

    /** 删除远程文件或目录镜像树。 */
    @Override
    public void deletePath(UUID ownerId, String path) {
        try {
            String query = "?owner_id=" + java.net.URLEncoder.encode(ownerId.toString(), StandardCharsets.UTF_8)
                    + "&path=" + java.net.URLEncoder.encode(path, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(deleteEndpoint + "/tree" + query))
                    .timeout(TIMEOUT)
                    .header(TOKEN_HEADER, token)
                    .header("Accept", "application/json")
                    .DELETE()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpClientSupport.limitedUtf8BodyHandler(64 * 1024));
            requireOk(response);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new FileStorageException(502, "文件镜像树删除中断", error);
        } catch (IOException error) {
            throw new FileStorageException(502, "文件镜像树删除失败", error);
        }
    }

    /** 镜像移动文件或目录。 */
    @Override
    public void movePath(UUID ownerId, String source, String destination, boolean overwrite) {
        pathMutation("/internal/v1/files/mirror/move", ownerId, source, destination, overwrite);
    }

    /** 镜像复制文件或目录。 */
    @Override
    public void copyPath(UUID ownerId, String source, String destination, boolean overwrite) {
        pathMutation("/internal/v1/files/mirror/copy", ownerId, source, destination, overwrite);
    }

    private void pathMutation(String path, UUID ownerId, String source, String destination, boolean overwrite) {
        try {
            Map<String, Object> body = Map.of("owner_id", ownerId.toString(), "source", source,
                    "destination", destination, "overwrite", overwrite);
            HttpRequest request = HttpRequest.newBuilder(endpoint(path))
                    .timeout(TIMEOUT)
                    .header(TOKEN_HEADER, token)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body),
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpClientSupport.limitedUtf8BodyHandler(64 * 1024));
            requireOk(response);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new FileStorageException(502, "文件镜像路径操作中断", error);
        } catch (IOException error) {
            throw new FileStorageException(502, "文件镜像路径操作失败", error);
        }
    }

    private void requireOk(HttpResponse<String> response) throws IOException {
        JsonNode root = objectMapper.readTree(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300
                || root == null || !root.path("ok").asBoolean(false)) {
            String detail = root == null ? "file service mirror failed"
                    : root.path("detail").asText(root.path("error").asText("file service mirror failed"));
            throw new FileStorageException(response.statusCode(), detail);
        }
    }

    private URI endpoint(String raw, String suffix) {
        try {
            URI base = new URI(raw == null ? "" : raw.trim().replaceAll("/+$", ""));
            String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || base.getHost() == null
                    || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException("file service URL is invalid");
            }
            String path = base.getPath() == null ? "" : base.getPath();
            return path.endsWith(suffix) ? base : URI.create(base + suffix);
        } catch (Exception error) {
            throw new IllegalArgumentException("file service URL is invalid", error);
        }
    }

    private URI endpoint(String suffix) {
        return URI.create(serviceRoot + suffix);
    }
}
