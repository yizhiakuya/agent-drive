package com.agentdrive.vision;

import com.agentdrive.net.HttpClientSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

/**
 * 调用 OpenAI Chat Completions 兼容的视觉模型，并把模型输出规整成固定 JSON 结构。
 *
 * <p>客户端只发送图片 bytes 和固定抽取提示词，不允许模型返回任意脚本或把原始响应直接
 * 作为索引正文；未知字段会被丢弃，缺失字段使用空值，保证下游 embedding 输入稳定。</p>
 */
public final class VisionModelClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String SCHEMA_VERSION = "image-description-v1";
    private static final byte[] PROBE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    private final ObjectMapper objectMapper;
    private final HttpClient client = HttpClientSupport.builder(TIMEOUT).build();

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
     * @return 经过 schema 规整的图片描述对象。
     * @throws IOException 请求或 JSON 编解码失败。
     * @throws InterruptedException HTTP 请求被中断。
     */
    public Map<String, Object> describe(VisionRuntimeConfig.Config config, byte[] image,
                                        String mediaType, String path)
            throws IOException, InterruptedException {
        String requestBody = objectMapper.writeValueAsString(request(config, image, mediaType, path));
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(endpoint(config.baseUrl()))
                        .timeout(TIMEOUT)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + config.apiKey())
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("vision provider returned HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) throw new IllegalStateException("vision provider returned empty content");
        return normalize(objectMapper.readTree(stripJsonFence(content)));
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
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
        String dataUri = "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(image);
        Map<String, Object> imagePart = Map.of(
                "type", "image_url",
                "image_url", Map.of("url", dataUri)
        );
        Map<String, Object> textPart = Map.of(
                "type", "text",
                "text", prompt(path)
        );
        return Map.of(
                "model", config.model(),
                "max_tokens", 1200,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(textPart, imagePart)
                ))
        );
    }

    /**
     * 固定视觉抽取提示词，约束输出字段并避免身份、情绪等敏感推断。
     * @param path 图片相对路径。
     * @return 请求中的抽取提示词。
     */
    private String prompt(String path) {
        return "请只根据图片中可见内容进行信息抽取。不要识别人名、推断身份、健康状况或情绪，"
                + "不要编造不可见事实。只返回一个 JSON 对象，不要 Markdown 代码围栏。文件路径仅供上下文："
                + path + "。JSON 必须包含以下字段："
                + "schema_version(固定为 image-description-v1), title, summary, scene, objects(数组，"
                + "每项含 label/count/attributes), text_in_image(字符串数组), colors(字符串数组), "
                + "tags(字符串数组), people_count(整数), time_of_day(字符串或 null), confidence(0 到 1 数字)。"
                + "看不清或无法确定的字段使用空字符串、空数组或 null。";
    }

    /**
     * 规范化模型 JSON，限制字段长度和数组规模，防止异常输出膨胀任务/索引。
     * @param raw 模型返回的 JSON 节点。
     * @return 固定字段顺序的结构化描述。
     */
    private Map<String, Object> normalize(JsonNode raw) {
        if (raw == null || !raw.isObject()) throw new IllegalStateException("vision response is not a JSON object");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", SCHEMA_VERSION);
        result.put("title", text(raw, "title", 160));
        result.put("summary", text(raw, "summary", 1200));
        result.put("scene", text(raw, "scene", 400));
        result.put("objects", objects(raw.path("objects")));
        result.put("text_in_image", strings(raw.path("text_in_image"), 20, 500));
        result.put("colors", strings(raw.path("colors"), 20, 80));
        result.put("tags", strings(raw.path("tags"), 30, 80));
        result.put("people_count", Math.max(0, Math.min(10000, raw.path("people_count").asInt(0))));
        result.put("time_of_day", raw.path("time_of_day").isNull() ? null : text(raw, "time_of_day", 80));
        double confidence = raw.path("confidence").isNumber() ? raw.path("confidence").asDouble() : 0.0;
        result.put("confidence", Double.isFinite(confidence) ? Math.max(0.0, Math.min(1.0, confidence)) : 0.0);
        return result;
    }

    /**
     * 规整对象识别数组。
     * @param raw 模型对象数组。
     * @return 每项包含 label、count 和 attributes 的列表。
     */
    private List<Map<String, Object>> objects(JsonNode raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!raw.isArray()) return result;
        for (JsonNode item : raw) {
            if (!item.isObject() || result.size() >= 50) continue;
            Map<String, Object> object = new LinkedHashMap<>();
            object.put("label", text(item, "label", 120));
            object.put("count", Math.max(0, Math.min(10000, item.path("count").asInt(1))));
            object.put("attributes", strings(item.path("attributes"), 20, 120));
            result.add(object);
        }
        return result;
    }

    /**
     * 读取并限制 JSON 字符串字段。
     * @param node JSON 对象。
     * @param field 字段名。
     * @param maxLength 最大字符数。
     * @return 去首尾空白的字段文本。
     */
    private String text(JsonNode node, String field, int maxLength) {
        String value = node.path(field).asText("").trim();
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /**
     * 读取并限制字符串数组。
     * @param node JSON 数组节点。
     * @param maxItems 最大项目数。
     * @param maxLength 单项最大字符数。
     * @return 去重后的非空字符串列表。
     */
    private List<String> strings(JsonNode node, int maxItems, int maxLength) {
        List<String> result = new ArrayList<>();
        if (!node.isArray()) return result;
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isBlank() && !result.contains(value)) {
                result.add(value.length() <= maxLength ? value : value.substring(0, maxLength));
            }
            if (result.size() >= maxItems) break;
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
