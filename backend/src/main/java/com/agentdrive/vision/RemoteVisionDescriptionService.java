package com.agentdrive.vision;

import com.agentdrive.files.FileContentPort;
import com.agentdrive.files.FileStorageException;
import com.agentdrive.net.HttpClientSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 通过内部 HTTP 契约调用独立 Content Service 的视觉端口适配器。
 *
 * <p>适配器只把 owner 当前配置快照和受限原始图片字节发送给内容服务，不暴露本地
 * 绝对路径，也不把 provider 原始响应或 API key 返回给上层。配置 URL 为空时由装配层
 * 选择 {@link VisionDescriptionService}，因此本类不会改变默认的模块化单体行为。</p>
 */
public final class RemoteVisionDescriptionService implements VisionDescriptionPort {
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final long MAX_BATCH_BYTES = 20L * 1024 * 1024;
    private static final int MAX_BATCH_IMAGES = 4;
    private static final String TOKEN_HEADER = "X-Content-Service-Token";
    private static final Map<String, String> IMAGE_TYPES = Map.of(
            ".png", "image/png",
            ".jpg", "image/jpeg",
            ".jpeg", "image/jpeg",
            ".gif", "image/gif",
            ".webp", "image/webp",
            ".bmp", "image/bmp"
    );

    private final VisionRuntimeConfig configs;
    private final FileContentPort files;
    private final ObjectMapper objectMapper;
    private final URI describeEndpoint;
    private final URI readyEndpoint;
    private final String token;
    private final HttpClient client = HttpClientSupport.builder(TIMEOUT).build();

    private record PreparedImage(String path, String mediaType, byte[] bytes, String imageId) {
    }

    /**
     * 创建远程视觉端口。
     *
     * @param serviceUrl Content Service 根地址，例如 {@code http://127.0.0.1:8010}
     * @param token 主 API 与 Content Service 之间的内部令牌
     * @param configs owner-scoped 视觉配置读取端口
     * @param files owner 文件内容读取端口
     * @param objectMapper JSON 编解码器
     */
    public RemoteVisionDescriptionService(String serviceUrl, String token,
                                           VisionRuntimeConfig configs, FileContentPort files,
                                           ObjectMapper objectMapper) {
        this.configs = configs;
        this.files = files;
        this.objectMapper = objectMapper;
        this.describeEndpoint = endpoint(serviceUrl, "/internal/v1/vision/describe");
        this.readyEndpoint = endpoint(serviceUrl, "/internal/v1/ready");
        this.token = token == null ? "" : token.trim();
    }

    /** 按最多四张、二十 MiB 原始字节批量请求内容服务。 */
    @Override
    public Map<String, Object> describeFiles(UUID userId, List<String> paths) {
        return describeFiles(userId, paths, null);
    }

    @Override
    public Map<String, Object> describeFiles(UUID userId, List<String> paths,
                                              Consumer<Map<String, Object>> progressListener) {
        return describeFiles(userId, paths, progressListener, null);
    }

    @Override
    public Map<String, Object> describeFiles(UUID userId, List<String> paths,
                                              Consumer<Map<String, Object>> progressListener,
                                              Consumer<List<Map<String, Object>>> batchListener) {
        Optional<VisionRuntimeConfig.Config> config = configs.find(userId);
        if (config.isEmpty() || config.get().apiKey() == null || config.get().apiKey().isBlank()) {
            return Map.of("ok", false, "error", "vision_not_configured", "items", List.of());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        List<PreparedImage> pending = new ArrayList<>();
        long pendingBytes = 0;
        int sequence = 0;
        for (String path : paths) {
            try {
                PreparedImage image = prepareImage(userId, path, "image-" + sequence++);
                if (!pending.isEmpty() && (pending.size() >= MAX_BATCH_IMAGES
                        || pendingBytes + image.bytes().length > MAX_BATCH_BYTES)) {
                    int before = items.size();
                    appendBatch(items, pending, config.get());
                    notifyBatch(batchListener, items, before);
                    reportProgress(progressListener, items, paths.size());
                    pending = new ArrayList<>();
                    pendingBytes = 0;
                }
                pending.add(image);
                pendingBytes += image.bytes().length;
            } catch (Exception error) {
                Map<String, Object> failed = failure(path, error);
                items.add(failed);
                if (batchListener != null) batchListener.accept(List.of(failed));
                reportProgress(progressListener, items, paths.size());
            }
        }
        if (!pending.isEmpty()) {
            int before = items.size();
            appendBatch(items, pending, config.get());
            notifyBatch(batchListener, items, before);
            reportProgress(progressListener, items, paths.size());
        }

        boolean anySuccess = items.stream().anyMatch(item -> item.get("description") instanceof String description
                && !description.isBlank());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", anySuccess);
        result.put("model", config.get().model());
        result.put("items", List.copyOf(items));
        if (!anySuccess) result.put("error", "vision_all_files_failed");
        return Map.copyOf(result);
    }

    private void notifyBatch(Consumer<List<Map<String, Object>>> listener,
                             List<Map<String, Object>> items, int before) {
        if (listener == null || before >= items.size()) return;
        listener.accept(List.copyOf(items.subList(before, items.size())));
    }

    private void reportProgress(Consumer<Map<String, Object>> listener,
                                List<Map<String, Object>> items, int total) {
        if (listener == null) return;
        int succeeded = (int) items.stream().filter(item -> item.get("description") instanceof String text
                && !text.isBlank()).count();
        listener.accept(Map.of(
                "phase", "vision",
                "message", "正在调用视觉模型分析图片",
                "completed", items.size(),
                "total", Math.max(0, total),
                "succeeded", succeeded,
                "failed", Math.max(0, items.size() - succeeded)
        ));
    }

    /** 检查 owner 配置并探测远程 Content Service。 */
    @Override
    public Map<String, Object> requireReady(UUID userId) {
        VisionRuntimeConfig.Config config = configs.find(userId)
                .orElseThrow(() -> new VisionProviderUnavailableException("vision_not_configured: 请先配置视觉模型和 API Key"));
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new VisionProviderUnavailableException("vision_not_configured: 请先配置视觉模型和 API Key");
        }
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(readyEndpoint)
                            .timeout(TIMEOUT)
                            .header(TOKEN_HEADER, token)
                            .header("Accept", "application/json")
                            .GET()
                            .build(),
                    HttpClientSupport.limitedUtf8BodyHandler(32 * 1024));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new VisionProviderUnavailableException("content_service_unavailable: HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (root == null || !root.path("ready").asBoolean(false)) {
                throw new VisionProviderUnavailableException("content_service_unavailable: service is not ready");
            }
            return Map.of("ready", true, "model", config.model(), "service", "content");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new VisionProviderUnavailableException("content_service_unavailable: request interrupted");
        } catch (IOException | RuntimeException error) {
            if (error instanceof VisionProviderUnavailableException unavailable) throw unavailable;
            throw new VisionProviderUnavailableException("content_service_unavailable: " + safeMessage(error));
        }
    }

    /** 判断是否为支持的图片扩展名。 */
    @Override
    public boolean isImage(String path) {
        return IMAGE_TYPES.containsKey(extension(path));
    }

    /** 请求单张图片描述，并保持与本地端口相同的返回结构。 */
    @Override
    public Map<String, Object> describeFile(UUID userId, String path) {
        Map<String, Object> response = describeFiles(userId, List.of(path));
        Object first = response.get("items") instanceof List<?> list && !list.isEmpty() ? list.get(0) : null;
        if (first instanceof Map<?, ?> item && item.get("description") instanceof String description
                && !description.isBlank()) {
            return Map.of("path", path, "mime_type", item.get("mime_type"),
                    "model", item.get("model"), "description", description);
        }
        String error = first instanceof Map<?, ?> item ? String.valueOf(valueOrDefault(item, "error", "vision_request_failed"))
                : String.valueOf(response.getOrDefault("error", "vision_request_failed"));
        throw new IllegalStateException(error);
    }

    private void appendBatch(List<Map<String, Object>> items, List<PreparedImage> batch,
                             VisionRuntimeConfig.Config config) {
        try {
            Map<String, Map<String, Object>> remoteItems = describeBatch(batch, config);
            for (PreparedImage image : batch) {
                Map<String, Object> remote = remoteItems.get(image.imageId());
                if (remote == null || !(remote.get("description") instanceof String description)
                        || description.isBlank()) {
                    items.add(failure(image.path(), new IllegalStateException("vision_description_missing")));
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("path", image.path());
                item.put("mime_type", image.mediaType());
                item.put("model", config.model());
                item.put("description", description);
                items.add(item);
            }
        } catch (Exception error) {
            for (PreparedImage image : batch) items.add(failure(image.path(), error));
        }
    }

    private Map<String, Map<String, Object>> describeBatch(List<PreparedImage> images,
                                                             VisionRuntimeConfig.Config config)
            throws IOException, InterruptedException {
        List<Map<String, Object>> requestImages = new ArrayList<>();
        for (PreparedImage image : images) {
            requestImages.add(Map.of(
                    "image_id", image.imageId(),
                    "path", image.path(),
                    "media_type", image.mediaType(),
                    "data", Base64.getEncoder().encodeToString(image.bytes())));
        }
        Map<String, Object> provider = Map.of(
                "provider", config.provider() == null ? "openai_compat" : config.provider(),
                "base_url", config.baseUrl(),
                "model", config.model(),
                "api_key", config.apiKey());
        Map<String, Object> request = Map.of("images", requestImages, "provider", provider);
        HttpRequest httpRequest = HttpRequest.newBuilder(describeEndpoint)
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
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("content_service returned HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        if (root == null || !root.path("ok").asBoolean(false)) {
            throw new IllegalStateException(root == null
                    ? "content_service returned invalid response"
                    : root.path("detail").asText(root.path("error").asText("content_service request failed")));
        }
        List<Map<String, Object>> returned = objectMapper.convertValue(root.path("items"),
                new TypeReference<>() { });
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> item : returned) {
            Object id = item.get("image_id");
            if (id instanceof String imageId && !result.containsKey(imageId)) result.put(imageId, item);
        }
        return result;
    }

    private PreparedImage prepareImage(UUID userId, String path, String imageId) {
        String mediaType = IMAGE_TYPES.get(extension(path));
        if (mediaType == null) throw new IllegalArgumentException("unsupported_image_type");
        byte[] bytes = files.readBytes(userId, path, MAX_IMAGE_BYTES);
        if (bytes.length == 0) throw new IllegalArgumentException("image_empty");
        return new PreparedImage(path, mediaType, bytes, imageId);
    }

    private Map<String, Object> failure(String path, Exception error) {
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("path", path);
        failed.put("ok", false);
        failed.put("error", safeMessage(error));
        return failed;
    }

    private URI endpoint(String raw, String suffix) {
        try {
            URI base = new URI(raw == null ? "" : raw.trim().replaceAll("/+$", ""));
            String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || base.getHost() == null
                    || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException("content service URL is invalid");
            }
            String path = base.getPath() == null ? "" : base.getPath();
            if (path.endsWith(suffix)) return base;
            return URI.create(base + suffix);
        } catch (Exception error) {
            throw new IllegalArgumentException("content service URL is invalid", error);
        }
    }

    private String extension(String path) {
        String name = path == null ? "" : path.toLowerCase(Locale.ROOT);
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index);
    }

    private static String safeMessage(Exception error) {
        if (error instanceof FileStorageException storage) return storage.getMessage();
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static Object valueOrDefault(Map<?, ?> values, String key, Object fallback) {
        Object value = values.get(key);
        return value == null ? fallback : value;
    }
}
