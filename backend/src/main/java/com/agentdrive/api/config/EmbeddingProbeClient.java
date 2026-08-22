package com.agentdrive.api.config;

import com.agentdrive.net.HttpClientSupport;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通过 Jina 兼容的 {@code /embeddings} 端点验证 embedding 配置。
 *
 * <p>探测发送固定的短文本和所选模型，仅返回 HTTP 状态、成功标志和模型名，不解析
 * 或持久化向量内容；请求使用应用代理和 20 秒超时，异常消息不会包含 API key。
 */
final class EmbeddingProbeClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_RESPONSE_BYTES = 1 * 1024 * 1024;
    private final HttpClient client = HttpClientSupport.builder(TIMEOUT).build();
    private final ObjectMapper objectMapper;

    /**
     * 创建 embedding 探测客户端。
     *
     * @param objectMapper 序列化探测请求 JSON 的映射器。
     */
    EmbeddingProbeClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 向 embedding Provider 发送一次最小请求以验证配置。
     *
     * @param baseUrl Provider 根地址；方法会追加 {@code /embeddings}。
     * @param model 请求使用的 embedding 模型。
     * @param apiKey 仅用于本次 Authorization 头，不写入返回映射。
     * @return 包含 {@code ok}、HTTP {@code status} 和成功模型名或安全错误消息的映射。
     */
    Map<String, Object> test(String baseUrl, String model, String apiKey) {
        try {
            URI endpoint = endpoint(baseUrl);
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "input", java.util.List.of("agent-drive connectivity probe")
            ));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpClientSupport.limitedUtf8BodyHandler(MAX_RESPONSE_BYTES));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", response.statusCode() >= 200 && response.statusCode() < 300);
            result.put("status", response.statusCode());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                result.put("error", "embedding provider returned HTTP " + response.statusCode());
            } else {
                result.put("model", model);
            }
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Map.of("ok", false, "error", "embedding request interrupted");
        } catch (Exception error) {
            return Map.of("ok", false, "error", safeMessage(error));
        }
    }

    /**
     * 校验并构造 embedding 端点 URI。
     *
     * @param raw Provider 根 URL。
     * @return 追加 {@code /embeddings} 的 HTTP(S) URI。
     * @throws IllegalArgumentException 地址为空、协议非法或包含凭据/query/fragment 时抛出。
     */
    private URI endpoint(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("base_url 不能为空");
        try {
            URI base = new URI(raw.trim().replaceAll("/+$", ""));
            String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || base.getHost() == null
                    || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException("base_url must be an http(s) URL without credentials, query, or fragment");
            }
            return URI.create(base + "/embeddings");
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("base_url is invalid", error);
        }
    }

    /**
     * 提取不含请求凭据的异常摘要。
     *
     * @param error 外部 HTTP 或 JSON 调用异常。
     * @return 异常消息；消息为空时返回异常简单类名。
     */
    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
