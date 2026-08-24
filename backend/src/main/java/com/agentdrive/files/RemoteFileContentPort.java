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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 通过内部 HTTP 契约读取独立 File Service 的 owner 文件内容。
 *
 * <p>该端口只传递 owner UUID、相对路径和字节上限，不共享主 API 的本地路径。
 * 响应会重新校验 owner、路径、大小和 MD5，远程服务返回的异常不会被伪装为空文件。</p>
 */
public final class RemoteFileContentPort implements FileContentPort {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_RESPONSE_BYTES = 80 * 1024 * 1024;
    private static final String TOKEN_HEADER = "X-File-Service-Token";

    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final String token;
    private final HttpClient client = HttpClientSupport.builder(TIMEOUT).build();

    /**
     * 创建远程文件内容端口。
     *
     * @param serviceUrl File Service 根地址，例如 {@code http://127.0.0.1:8020}
     * @param token 内部服务令牌
     * @param objectMapper JSON 编解码器
     */
    public RemoteFileContentPort(String serviceUrl, String token, ObjectMapper objectMapper) {
        this.endpoint = endpoint(serviceUrl);
        this.token = token == null ? "" : token.trim();
        this.objectMapper = objectMapper;
    }

    /** 请求并校验 owner 文件原始 bytes。 */
    @Override
    public byte[] readBytes(UUID ownerId, String path, long maxBytes) {
        if (ownerId == null || path == null || path.isBlank() || maxBytes <= 0
                || maxBytes > Integer.MAX_VALUE || token.isBlank()) {
            throw new FileStorageException(400, "远程文件读取参数无效");
        }
        try {
            Map<String, Object> request = Map.of(
                    "owner_id", ownerId.toString(),
                    "path", path,
                    "max_bytes", maxBytes);
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                    .timeout(TIMEOUT)
                    .header(TOKEN_HEADER, token)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Cache-Control", "no-store")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request),
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(httpRequest,
                    HttpClientSupport.limitedUtf8BodyHandler(MAX_RESPONSE_BYTES));
            JsonNode root = objectMapper.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || root == null || !root.path("ok").asBoolean(false)) {
                String detail = root == null ? "远程文件服务请求失败"
                        : root.path("detail").asText(root.path("error").asText("远程文件服务请求失败"));
                throw new FileStorageException(response.statusCode(), detail);
            }
            if (!ownerId.toString().equals(root.path("owner_id").asText())
                    || !path.equals(root.path("path").asText())) {
                throw new FileStorageException(502, "远程文件服务响应归属不匹配");
            }
            byte[] bytes = Base64.getDecoder().decode(root.path("data").asText(""));
            long declaredSize = root.path("size_bytes").asLong(-1);
            if (declaredSize != bytes.length || bytes.length > maxBytes) {
                throw new FileStorageException(502, "远程文件服务响应大小不匹配");
            }
            String expectedMd5 = root.path("content_md5").asText("");
            String actualMd5 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("MD5").digest(bytes));
            if (!actualMd5.equalsIgnoreCase(expectedMd5)) {
                throw new FileStorageException(502, "远程文件服务响应校验失败");
            }
            return bytes;
        } catch (FileStorageException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new FileStorageException(502, "远程文件服务请求中断", error);
        } catch (IOException | RuntimeException | java.security.NoSuchAlgorithmException error) {
            throw new FileStorageException(502, "远程文件服务请求失败", error);
        }
    }

    private URI endpoint(String raw) {
        try {
            URI base = new URI(raw == null ? "" : raw.trim().replaceAll("/+$", ""));
            String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || base.getHost() == null
                    || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException("file service URL is invalid");
            }
            String path = base.getPath() == null ? "" : base.getPath();
            return path.endsWith("/internal/v1/files/content")
                    ? base : URI.create(base + "/internal/v1/files/content");
        } catch (Exception error) {
            throw new IllegalArgumentException("file service URL is invalid", error);
        }
    }
}
