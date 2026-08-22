package com.agentdrive.api.config;

import com.agentdrive.agent.ChatModelCapabilities;
import com.agentdrive.net.HttpClientSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 用 JDK HTTP Client 探测 LLM Provider 的 {@code /models} 端点。
 *
 * <p>请求复用应用代理配置并设置 20 秒超时；Anthropic 使用 {@code x-api-key} 和版本头，
 * 其他 Provider 使用 Bearer 头。响应支持直接数组和 OpenAI 风格 {@code data} 数组，
 * 提取模型 ID 和可用的图片输入能力提示，错误转换为不含响应正文或 key 的
 * {@link ProbeResult}。能力提示不是 Provider 的统一标准：明确的布尔/模态字段优先，
 * 没有明确字段时回退到已知模型名规则，未知模型保持 {@code false}。
 */
final class ProviderProbeClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient client;
    private final ObjectMapper objectMapper;

    /**
     * 创建模型探测客户端。
     *
     * @param objectMapper 解析 Provider JSON 响应的映射器。
     */
    ProviderProbeClient(ObjectMapper objectMapper) {
        this.client = HttpClientSupport.builder(TIMEOUT).build();
        this.objectMapper = objectMapper;
    }

    /**
     * 请求 Provider 模型列表并转换为统一探测结果。
     *
     * @param provider 内部 Provider 标识；anthropic 使用专用鉴权头。
     * @param baseUrl Provider 根地址，方法会追加 {@code /models}。
     * @param apiKey 仅用于本次 HTTP 鉴权，不写入结果。
     * @return 成功时包含模型 ID 列表，HTTP/网络/解析失败时包含安全错误消息。
     */
    ProbeResult listModels(String provider, String baseUrl, String apiKey) {
        final URI endpoint;
        try {
            endpoint = endpoint(baseUrl);
        } catch (IllegalArgumentException error) {
            return ProbeResult.failure(error.getMessage());
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET();
        if ("anthropic".equals(provider)) {
            request.header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01");
        } else {
            request.header("Authorization", "Bearer " + apiKey);
        }
        try {
            HttpResponse<String> response = client.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return ProbeResult.failure("provider returned HTTP " + response.statusCode());
            }
            ParsedModels parsed = parseModels(provider, response.body());
            return ProbeResult.success(parsed.models(), parsed.capabilities());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ProbeResult.failure("provider request interrupted");
        } catch (Exception error) {
            return ProbeResult.failure("provider request failed: " + safeMessage(error));
        }
    }

    /**
     * 校验 Provider 地址并构造模型列表端点。
     *
     * @param baseUrl Provider 根 URL。
     * @return 追加 {@code /models} 的 HTTP(S) URI。
     * @throws IllegalArgumentException 地址为空、协议不是 HTTP(S) 或含凭据/query/fragment 时抛出。
     */
    private URI endpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("base_url must not be blank");
        }
        String value = baseUrl.trim();
        try {
            URI base = new URI(value);
            String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || base.getHost() == null
                    || base.getUserInfo() != null
                    || base.getFragment() != null
                    || base.getQuery() != null) {
                throw new IllegalArgumentException("base_url must be an http(s) URL without credentials, query, or fragment");
            }
            return new URI(value.replaceAll("/+$", "") + "/models");
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("base_url is invalid", error);
        }
    }

    /**
     * 从 Provider JSON 中提取模型 ID。
     *
     * @param body Provider 返回的 JSON 文本；支持字符串数组、对象数组和 {@code data} 包装。
     * @return 非空模型 ID 的不可变列表。
     * @throws IllegalArgumentException JSON 无法解析或不含数组时抛出。
     */
    private ParsedModels parseModels(String provider, String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode models = root != null && root.isArray() ? root : root == null ? null : root.get("data");
            if (models == null || !models.isArray()) {
                throw new IllegalArgumentException("provider response does not contain a models array");
            }
            List<String> result = new ArrayList<>();
            Map<String, Boolean> capabilities = new LinkedHashMap<>();
            for (JsonNode model : models) {
                JsonNode id = model.isTextual() ? model : model.get("id");
                if (id != null && id.isTextual() && !id.asText().isBlank()) {
                    String modelId = id.asText().trim();
                    if (!capabilities.containsKey(modelId)) {
                        Boolean explicit = explicitImageCapability(model);
                        capabilities.put(modelId, explicit != null
                                ? explicit
                                : ChatModelCapabilities.supportsImages(provider, modelId));
                        result.add(modelId);
                    }
                }
            }
            return new ParsedModels(List.copyOf(result), Collections.unmodifiableMap(capabilities));
        } catch (Exception error) {
            throw new IllegalArgumentException("provider response has an invalid models shape", error);
        }
    }

    /**
     * 从非标准 Provider 元数据提取明确的图片输入能力。
     *
     * <p>这里只接受有限、可审计的字段名，避免把任意描述文本误判为视觉能力。
     * 对数组模态字段，只有包含 image/vision 才返回 true；如果数组明确只列出
     * text/audio 等已知非图片模态则返回 false，其他形状交给模型名兜底。</p>
     */
    private Boolean explicitImageCapability(JsonNode model) {
        if (model == null || !model.isObject()) return null;
        for (String key : List.of(
                "supports_images", "supportsImages", "supports_vision", "supportsVision",
                "image_input", "imageInput", "vision", "multimodal")) {
            JsonNode value = model.get(key);
            if (value != null && value.isBoolean()) return value.booleanValue();
        }
        for (String key : List.of(
                "input_modalities", "inputModalities", "supported_modalities",
                "supportedModalities", "modalities", "capabilities")) {
            Boolean capability = capabilityFromNode(model.get(key));
            if (capability != null) return capability;
        }
        return capabilityFromNode(model.get("architecture"));
    }

    private Boolean capabilityFromNode(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isBoolean()) return node.booleanValue();
        if (node.isTextual()) return modalityValue(node.asText());
        if (node.isArray()) {
            boolean recognized = false;
            for (JsonNode value : node) {
                if (!value.isTextual()) continue;
                String normalized = value.asText().trim().toLowerCase(Locale.ROOT);
                if (isImageModality(normalized)) return true;
                if (NON_IMAGE_MODALITIES.contains(normalized)) recognized = true;
            }
            return recognized ? Boolean.FALSE : null;
        }
        if (!node.isObject()) return null;
        for (String key : List.of(
                "supports_images", "supportsImages", "supports_vision", "supportsVision",
                "image_input", "imageInput", "vision", "multimodal")) {
            JsonNode value = node.get(key);
            if (value != null && value.isBoolean()) return value.booleanValue();
        }
        for (String key : List.of(
                "input_modalities", "inputModalities", "supported_modalities",
                "supportedModalities", "modalities")) {
            Boolean capability = capabilityFromNode(node.get(key));
            if (capability != null) return capability;
        }
        return null;
    }

    private static Boolean modalityValue(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return null;
        if (isImageModality(normalized)) return Boolean.TRUE;
        return NON_IMAGE_MODALITIES.contains(normalized) ? Boolean.FALSE : null;
    }

    private static boolean isImageModality(String value) {
        return value.equals("image")
                || value.equals("images")
                || value.equals("vision")
                || value.equals("image_url")
                || value.equals("image-url")
                || value.equals("multimodal");
    }

    private static final Set<String> NON_IMAGE_MODALITIES = Set.of(
            "text", "audio", "video", "tool", "function", "code"
    );

    /**
     * 提取适合返回给客户端的异常消息。
     *
     * @param error 外部 HTTP/JSON 调用异常。
     * @return 异常消息，缺失时退回异常简单类名。
     */
    private static String safeMessage(Exception error) {

        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    /**
     * 模型探测的内部结果；成功时携带模型列表及图片输入能力，失败时携带安全错误消息。
     */
    record ProbeResult(boolean ok, List<String> models, Map<String, Boolean> capabilities, String error) {
        /**
         * 转换为 HTTP/内部 backend API 使用的 JSON 映射。
         *
         * @return 成功结果含 {@code models} 和 {@code model_capabilities}，失败结果含 {@code error}，两者都含 {@code ok}。
         */
        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", ok);
            if (ok) {
                result.put("models", models);
                result.put("model_capabilities", capabilities);
            } else {
                result.put("error", error);
            }
            return result;
        }

        /**
         * 创建成功的模型探测结果。
         *
         * @param models 已从 Provider 响应提取的模型 ID。
         * @param capabilities 按模型 ID 编排的图片输入能力。
         * @return {@code ok=true} 且保存模型列表的结果。
         */
        static ProbeResult success(List<String> models, Map<String, Boolean> capabilities) {
            return new ProbeResult(true, models, capabilities, null);
        }

        /**
         * 创建失败的模型探测结果。
         *
         * @param error 不含密钥和完整外部响应正文的错误消息。
         * @return {@code ok=false} 且模型列表为空的结果。
         */
        static ProbeResult failure(String error) {
            return new ProbeResult(false, List.of(), Map.of(), error);
        }
    }

    private record ParsedModels(List<String> models, Map<String, Boolean> capabilities) {
    }
}
