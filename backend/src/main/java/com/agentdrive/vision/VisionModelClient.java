package com.agentdrive.vision;

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
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 调用 OpenAI Chat Completions 兼容的视觉模型，并把模型输出规整成一段综合描述。
 *
 * <p>客户端只发送图片 bytes 和固定描述提示词，不允许模型返回任意脚本；描述正文直接
 * 作为下游索引输入，避免把大量低价值结构化字段重复写入向量。</p>
 */
public final class VisionModelClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_BATCH_IMAGES = 4;
    private static final int MAX_DESCRIPTION_CHARS = 6000;
    private static final Pattern BATCH_IMAGE_MARKER = Pattern.compile(
            "(?m)^\\s*image_id\\s*[:=]\\s*([A-Za-z0-9_-]{1,32})\\s*$");
    private static final byte[] PROBE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    private final ObjectMapper objectMapper;
    private final HttpClient client = HttpClientSupport.builder(TIMEOUT).build();

    /** A bounded image payload used by a multi-image request. */
    public record ImageInput(String imageId, byte[] image, String mediaType) {
    }

    /**
     * 创建视觉模型 HTTP 客户端。
     * @param objectMapper JSON 请求/响应映射器。
     */
    public VisionModelClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 请求视觉模型识别图片并返回固定结构描述。
     * @param config 当前 owner 的视觉模型配置。
     * @param image 图片字节。
     * @param mediaType 图片 MIME 类型。
     * @param path 文件相对路径，仅用于提示模型不要猜测文件名之外的事实。
     * @return 一段综合图片描述。
     * @throws IOException 请求或 JSON 编解码失败。
     * @throws InterruptedException HTTP 请求被中断。
     */
    public String describe(VisionRuntimeConfig.Config config, byte[] image,
                           String mediaType, String path)
            throws IOException, InterruptedException {
        return normalizeDescription(content(complete(config, request(config, image, mediaType, path))));
    }

    /**
     * Analyze several images in one multimodal request, preserving one result per image.
     * This is deliberately uncached: every explicit indexing request may regenerate descriptions.
     *
     * @param config current owner vision configuration
     * @param images bounded image inputs with unique server-generated IDs
     * @return plain-text descriptions keyed by image ID
     * @throws IOException request or JSON decoding failure
     * @throws InterruptedException interrupted HTTP request
     */
    public Map<String, String> describeBatch(VisionRuntimeConfig.Config config,
                                             List<ImageInput> images)
            throws IOException, InterruptedException {
        if (images == null || images.isEmpty() || images.size() > MAX_BATCH_IMAGES) {
            throw new IllegalArgumentException("vision batch must contain 1 to " + MAX_BATCH_IMAGES + " images");
        }
        Set<String> expected = new java.util.LinkedHashSet<>();
        for (ImageInput image : images) {
            if (image == null || image.imageId() == null
                    || !image.imageId().matches("[A-Za-z0-9_-]{1,32}")
                    || image.image() == null || image.image().length == 0
                    || image.mediaType() == null || image.mediaType().isBlank()
                    || !expected.add(image.imageId())) {
                throw new IllegalArgumentException("vision batch contains invalid or duplicate image input");
            }
        }
        String response = content(complete(config, batchRequest(config, images)));
        return parseBatchDescriptions(response, expected);
    }

    /**
     * 使用内置 1x1 图片执行配置连接测试。
     * @param config 待测试的视觉模型配置。
     * @return ok、HTTP 状态和模型名称；失败时不返回原始 provider body。
     */
    public Map<String, Object> test(VisionRuntimeConfig.Config config) {
        try {
            describe(config, PROBE_PNG, "image/png", "vision-connectivity-probe.png");
            return Map.of("ok", true, "model", config.model());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Map.of("ok", false, "error", "vision request interrupted");
        } catch (Exception error) {
            return Map.of("ok", false, "error", safeMessage(error));
        }
    }

    /**
     * 查询视觉 provider 的模型目录，供设置页选择可用视觉模型。
     *
     * <p>视觉模型和文本 LLM 共用 OpenAI 兼容的 {@code /models} 约定，但使用视觉配置
     * 自己的 API key；请求只返回模型 ID，不会把 key 或 provider 原始响应带回客户端。</p>
     *
     * @param config 当前 owner 的视觉配置快照；只使用 provider、地址和 API key。
     * @return 成功时包含模型 ID 列表，失败时包含安全错误消息。
     */
    public Map<String, Object> listModels(VisionRuntimeConfig.Config config) {
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(modelsEndpoint(config.baseUrl()))
                            .timeout(TIMEOUT)
                            .header("Accept", "application/json")
                            .header("Authorization", "Bearer " + config.apiKey())
                            .GET()
                            .build(),
                    HttpClientSupport.limitedUtf8BodyHandler(MAX_RESPONSE_BYTES));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Map.of("ok", false, "error", "vision provider returned HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode models = root != null && root.isArray() ? root : root == null ? null : root.get("data");
            if (models == null || !models.isArray()) {
                return Map.of("ok", false, "error", "vision provider response has an invalid models shape");
            }
            List<String> result = new ArrayList<>();
            for (JsonNode model : models) {
                JsonNode id = model.isTextual() ? model : model.get("id");
                if (id != null && id.isTextual() && !id.asText().isBlank()) {
                    result.add(id.asText());
                }
            }
            return Map.of("ok", true, "models", List.copyOf(result));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Map.of("ok", false, "error", "vision model request interrupted");
        } catch (Exception error) {
            return Map.of("ok", false, "error", "vision model request failed: " + safeMessage(error));
        }
    }

    /**
     * 构造 OpenAI 兼容的多模态请求体。
     * @param config 视觉模型配置。
     * @param image 图片 bytes。
     * @param mediaType 图片 MIME 类型。
     * @param path 文件相对路径。
     * @return 可序列化的请求 Map。
     */
    private Map<String, Object> request(VisionRuntimeConfig.Config config, byte[] image,
                                        String mediaType, String path) {
        return Map.of(
                "model", config.model(),
                "max_tokens", 1200,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(textPart(prompt(path)), imagePart(image, mediaType))
                ))
        );
    }

    /** Construct a request whose response contains an independent item for every image ID. */
    private Map<String, Object> batchRequest(VisionRuntimeConfig.Config config,
                                             List<ImageInput> images) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(textPart(batchPrompt(images)));
        for (ImageInput image : images) {
            content.add(textPart("以下图片的 image_id 是 " + image.imageId() + "。只描述这张图片，不要与其他图片合并。"));
            content.add(imagePart(image.image(), image.mediaType()));
        }
        return Map.of(
                "model", config.model(),
                "max_tokens", Math.min(4800, 1200 * images.size()),
                "messages", List.of(Map.of("role", "user", "content", content))
        );
    }

    private Map<String, Object> textPart(String text) {
        return Map.of("type", "text", "text", text);
    }

    private Map<String, Object> imagePart(byte[] image, String mediaType) {
        String dataUri = "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(image);
        // Preserve the original bytes and request high visual detail; the provider may still
        // tile or resize internally according to its own vision protocol.
        return Map.of("type", "image_url", "image_url", Map.of("url", dataUri, "detail", "high"));
    }

    /** Send a JSON request and return the provider response tree. */
    private JsonNode complete(VisionRuntimeConfig.Config config, Map<String, Object> request)
            throws IOException, InterruptedException {
        String requestBody = objectMapper.writeValueAsString(request);
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(endpoint(config.baseUrl()))
                        .timeout(TIMEOUT)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + config.apiKey())
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                        .build(),
                HttpClientSupport.limitedUtf8BodyHandler(MAX_RESPONSE_BYTES));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("vision provider returned HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private String content(JsonNode root) {
        String content = root == null ? "" : root.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) throw new IllegalStateException("vision provider returned empty content");
        return content;
    }

    /**
     * 固定视觉描述提示词，要求输出一段可检索的综合文字并避免身份、情绪等敏感推断。
     * @param path 图片相对路径。
     * @return 请求中的抽取提示词。
     */
    private String prompt(String path) {
        return "请只根据图片中可见内容，写一段详细但紧凑、可检索的综合描述。"
                + "描述图片的整体场景和页面/画面结构、主要人物或物体、它们之间的关系、重要的可见文字语义，"
                + "并尽量保留可辨认的标题、按钮、专有名词、数字、金额、日期和品牌词（不需要逐字 OCR），"
                + "颜色和视觉风格，以及与理解内容有关的状态或动作。不要写字段名、列表或 JSON，"
                + "直接返回一段自然语言；不要识别人名、推断身份、健康状况或情绪，不要编造不可见事实。"
                + "尽量保留图片中具体且可检索的内容，避免只写‘一张图片’之类的空泛描述。"
                + "文件路径仅供上下文，不要把路径当作图片事实：" + path + "。";
    }

    private String batchPrompt(List<ImageInput> images) {
        String ids = images.stream().map(ImageInput::imageId).collect(java.util.stream.Collectors.joining(", "));
        return "请分别分析以下图片，不能比较或合并图片内容。每张图片输出一段自然语言综合描述，"
                + "不要 JSON、不要 Markdown 列表、不要逐字 OCR。每段描述前单独一行写 image_id: 对应 ID，"
                + "下一行开始写该图片的完整描述；ID 只能使用以下值：" + ids + "。"
                + "描述应覆盖场景/结构、主要人物或物体、关系、标题/按钮/专有名词/数字等重要文字语义、颜色风格和可见状态。"
                + "不要推断身份、健康状况或情绪，不要编造不可见事实。";
    }

    /** 规整单段描述并兼容模型偶尔返回的旧 JSON 字段。 */
    private String normalizeDescription(String raw) {
        String value = stripJsonFence(raw);
        try {
            JsonNode parsed = objectMapper.readTree(value);
            if (parsed != null && parsed.isTextual()) value = parsed.asText();
            else if (parsed != null && parsed.isObject()) {
                List<String> parts = new ArrayList<>();
                for (String field : List.of("description", "content_description", "summary", "scene")) {
                    String part = parsed.path(field).asText("").trim();
                    if (!part.isBlank() && !parts.contains(part)) parts.add(part);
                }
                if (!parts.isEmpty()) value = String.join("。", parts);
            }
        } catch (Exception ignored) {
            // Plain text is the canonical response; malformed JSON-looking text remains text.
        }
        value = value.replaceAll("\\s+", " ").trim();
        if (value.isBlank()) throw new IllegalStateException("vision provider returned empty description");
        return value.length() <= MAX_DESCRIPTION_CHARS
                ? value : value.substring(0, MAX_DESCRIPTION_CHARS - 1) + "…";
    }

    /** 解析多图响应的轻量 image_id 分隔行，不要求模型生成复杂 JSON。 */
    private Map<String, String> parseBatchDescriptions(String raw, Set<String> expected) {
        String value = stripJsonFence(raw);
        Matcher matcher = BATCH_IMAGE_MARKER.matcher(value);
        Map<String, String> result = new LinkedHashMap<>();
        String currentId = null;
        int descriptionStart = -1;
        while (matcher.find()) {
            if (currentId != null) {
                result.put(currentId, normalizeDescription(value.substring(descriptionStart, matcher.start())));
            }
            currentId = matcher.group(1);
            if (!expected.contains(currentId) || result.containsKey(currentId)) {
                throw new IllegalStateException("vision batch response image_id mismatch");
            }
            descriptionStart = matcher.end();
        }
        if (currentId == null) throw new IllegalStateException("vision batch response has no image_id markers");
        result.put(currentId, normalizeDescription(value.substring(descriptionStart)));
        if (!result.keySet().equals(expected)) {
            throw new IllegalStateException("vision batch response is missing an image description");
        }
        return result;
    }

    /**
     * 去除模型可能添加的 JSON Markdown 围栏。
     * @param value 模型返回文本。
     * @return 可交给 Jackson 解析的 JSON 文本。
     */
    private String stripJsonFence(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            int newline = trimmed.indexOf('\n');
            return newline >= 0 ? trimmed.substring(newline + 1, trimmed.length() - 3).trim()
                    : trimmed.substring(3, trimmed.length() - 3).trim();
        }
        return trimmed;
    }

    /**
     * 校验并拼接 Chat Completions endpoint。
     * @param raw 配置中的 HTTP(S) 基础地址。
     * @return 请求 endpoint。
     */
    private URI endpoint(String raw) {
        try {
            URI base = new URI(raw == null ? "" : raw.trim().replaceAll("/+$", ""));
            String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || base.getHost() == null
                    || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException("vision base_url is invalid");
            }
            String path = base.getPath() == null ? "" : base.getPath();
            return URI.create(base + (path.endsWith("/chat/completions") ? "" : "/chat/completions"));
        } catch (Exception error) {
            throw new IllegalArgumentException("vision base_url is invalid", error);
        }
    }

    /**
     * 校验并拼接 OpenAI 兼容的模型目录 endpoint。
     * @param raw 配置中的 HTTP(S) 基础地址。
     * @return 请求模型列表的 URI。
     */
    private URI modelsEndpoint(String raw) {
        try {
            URI base = new URI(raw == null ? "" : raw.trim().replaceAll("/+$", ""));
            String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || base.getHost() == null
                    || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException("vision base_url is invalid");
            }
            String path = base.getPath() == null ? "" : base.getPath();
            return URI.create(base + (path.endsWith("/models") ? "" : "/models"));
        } catch (Exception error) {
            throw new IllegalArgumentException("vision base_url is invalid", error);
        }
    }

    /**
     * 生成不包含 provider 原始响应的安全错误文本。
     * @param error 外部请求异常。
     * @return 稳定错误文本。
     */
    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
