package com.agentdrive.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * 内容理解业务：校验批次、调用视觉 Provider 并返回纯文本描述。
 * 服务不访问主 API 数据库或文件系统，也不持久化图片和描述。
 */
@Service
public final class ContentDescriptionService {
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final Pattern MARKER = Pattern.compile("(?m)^\\s*image_id\\s*[:=]\\s*([A-Za-z0-9_-]{1,64})\\s*$");
    private final ContentServiceProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** 创建内容理解服务。 */
    public ContentDescriptionService(ContentServiceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 返回当前服务可用状态，不泄露 Provider 密钥。 */
    public Map<String, Object> ready() {
        boolean providerConfigured = !properties.baseUrl().isBlank()
                && !properties.model().isBlank() && !properties.apiKey().isBlank();
        return Map.of("ready", !properties.internalToken().isBlank(), "service", "content",
                "provider_configured", providerConfigured, "provider", properties.provider(),
                "model", properties.model());
    }

    /** 校验请求并执行一批视觉描述。 */
    public Map<String, Object> describe(ContentDescriptionController.DescribeRequest request) {
        List<ContentDescriptionController.ImageRequest> images = request.images();
        if (images.size() > properties.maxImages()) throw new IllegalArgumentException("too many images");
        Set<String> ids = new java.util.LinkedHashSet<>();
        long totalBytes = 0;
        long encodedChars = 0;
        for (ContentDescriptionController.ImageRequest image : images) {
            if (!ids.add(image.imageId()) || !image.mediaType().toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
                throw new IllegalArgumentException("invalid image batch");
            }
            byte[] bytes = decode(image.data());
            if (bytes.length > properties.maxImageBytes()) throw new IllegalArgumentException("image_too_large");
            totalBytes += bytes.length;
            encodedChars += image.data().length();
        }
        if (totalBytes > maxBatchBytes()) {
            throw new IllegalArgumentException("image_batch_too_large");
        }
        if (encodedChars > properties.maxRequestBytes()) {
            throw new IllegalArgumentException("image_request_too_large");
        }
        ProviderSettings settings = resolveProvider(request.provider());
        String providerText = requestProvider(images, settings);
        Map<String, String> descriptions = parseBatch(providerText, ids);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ContentDescriptionController.ImageRequest image : images) {
            items.add(Map.of("image_id", image.imageId(), "path", image.path(),
                    "description", descriptions.get(image.imageId()), "model", settings.model()));
        }
        return Map.of("ok", true, "items", items, "model", settings.model());
    }

    private long maxBatchBytes() {
        return Math.min(properties.maxImageBytes() * (long) properties.maxImages(),
                Math.max(properties.maxImageBytes(), properties.maxRequestBytes() * 3L / 4L));
    }

    private byte[] decode(String value) {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length == 0) throw new IllegalArgumentException("image data is empty");
            return bytes;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("image data is not valid Base64", error);
        }
    }

    private String requestProvider(List<ContentDescriptionController.ImageRequest> images, ProviderSettings settings) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", batchPrompt(images)));
        for (ContentDescriptionController.ImageRequest image : images) {
            content.add(Map.of("type", "text", "text", "image_id: " + image.imageId()));
            byte[] bytes = decode(image.data());
            String dataUri = "data:" + image.mediaType() + ";base64," + Base64.getEncoder().encodeToString(bytes);
            content.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUri, "detail", "high")));
        }
        Map<String, Object> request = Map.of("model", settings.model(),
                "max_tokens", Math.min(4800, 1200 * images.size()),
                "messages", List.of(Map.of("role", "user", "content", content)));
        try {
            URI endpoint = endpoint(settings.baseUrl());
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + settings.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(httpRequest, limitedBodyHandler(properties.maxResponseBytes()));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("vision provider returned HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            String text = root.path("choices").path(0).path("message").path("content").asText("").trim();
            if (text.isBlank()) throw new IllegalStateException("vision provider returned empty content");
            return text;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("vision provider request interrupted", error);
        } catch (IOException error) {
            throw new IllegalStateException("vision provider request failed", error);
        }
    }

    private String batchPrompt(List<ContentDescriptionController.ImageRequest> images) {
        String ids = images.stream().map(ContentDescriptionController.ImageRequest::imageId)
                .reduce((a, b) -> a + ", " + b).orElse("");
        return "分别分析以下图片，不能比较或合并图片内容。每张图片输出一段自然语言综合描述，"
                + "不要 JSON、不要 Markdown 列表、不要逐字 OCR。每段必须以单独一行 image_id: ID 开头，"
                + "ID 只能使用：" + ids + "。描述场景/结构、具体人物或物体、关系、重要文字语义、颜色风格和状态；"
                + "不要推断身份、健康状况或情绪，不要编造不可见事实。";
    }

    private Map<String, String> parseBatch(String text, Set<String> expected) {
        Matcher matcher = MARKER.matcher(text);
        Map<String, String> result = new LinkedHashMap<>();
        String current = null;
        int start = -1;
        while (matcher.find()) {
            if (current != null) result.put(current, clean(text.substring(start, matcher.start())));
            current = matcher.group(1);
            if (!expected.contains(current) || result.containsKey(current)) throw new IllegalStateException("provider image_id mismatch");
            start = matcher.end();
        }
        if (current == null) throw new IllegalStateException("provider returned no image descriptions");
        result.put(current, clean(text.substring(start)));
        if (!result.keySet().equals(expected) || result.values().stream().anyMatch(String::isBlank)) {
            throw new IllegalStateException("provider image descriptions are incomplete");
        }
        return result;
    }

    private String clean(String value) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() > 6000) normalized = normalized.substring(0, 5999) + "…";
        return normalized;
    }

    private URI endpoint(String baseUrl) {
        try {
            URI base = new URI(baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", ""));
            String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || base.getHost() == null
                    || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException("content provider base_url is invalid");
            }
            String path = base.getPath() == null ? "" : base.getPath();
            return URI.create(base + (path.endsWith("/chat/completions") ? "" : "/chat/completions"));
        } catch (Exception error) {
            throw new IllegalArgumentException("content provider base_url is invalid", error);
        }
    }

    /** 解析 provider 配置；请求快照完整时优先使用 owner 配置，否则使用服务环境变量。 */
    private ProviderSettings resolveProvider(ContentDescriptionController.ProviderRequest override) {
        String provider = clean(override == null ? null : override.provider(), properties.provider());
        String baseUrl = clean(override == null ? null : override.baseUrl(), properties.baseUrl());
        String model = clean(override == null ? null : override.model(), properties.model());
        String apiKey = clean(override == null ? null : override.apiKey(), properties.apiKey());
        boolean hasAnyOverride = override != null && java.util.stream.Stream.of(
                override.provider(), override.baseUrl(), override.model(), override.apiKey())
                .anyMatch(value -> value != null && !value.isBlank());
        if (hasAnyOverride && (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank())) {
            throw new IllegalArgumentException("provider snapshot is incomplete");
        }
        if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) {
            throw new IllegalStateException("content provider is not configured");
        }
        if (!"openai_compat".equalsIgnoreCase(provider)) {
            throw new IllegalArgumentException("unsupported content provider");
        }
        return new ProviderSettings(provider, baseUrl, model, apiKey);
    }

    private String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record ProviderSettings(String provider, String baseUrl, String model, String apiKey) {
    }

    /** 以流式 subscriber 限制 provider 响应体，超限时取消订阅而不是先完整读入内存。 */
    private HttpResponse.BodyHandler<String> limitedBodyHandler(int maxBytes) {
        return responseInfo -> new BoundedUtf8Subscriber(maxBytes);
    }

    private static final class BoundedUtf8Subscriber implements HttpResponse.BodySubscriber<String> {
        private final int maxBytes;
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private int total;

        private BoundedUtf8Subscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<String> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            try {
                for (ByteBuffer buffer : buffers) {
                    int remaining = buffer.remaining();
                    if (remaining > maxBytes - total) {
                        subscription.cancel();
                        body.completeExceptionally(new IllegalArgumentException("provider response exceeds size limit"));
                        return;
                    }
                    byte[] bytes = new byte[remaining];
                    buffer.get(bytes);
                    output.writeBytes(bytes);
                    total += remaining;
                }
                subscription.request(1);
            } catch (RuntimeException error) {
                body.completeExceptionally(error);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable instanceof IOException
                    ? new UncheckedIOException((IOException) throwable) : throwable);
        }

        @Override
        public void onComplete() {
            body.complete(output.toString(StandardCharsets.UTF_8));
        }
    }
}
