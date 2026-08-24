package com.agentdrive.content;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** 提供仅供主 API 调用的内容理解内部接口。 */
@RestController
@RequestMapping("/internal/v1")
public final class ContentDescriptionController {
    private static final String TOKEN_HEADER = "X-Content-Service-Token";
    private final ContentServiceProperties properties;
    private final ContentDescriptionService service;

    /** 创建内容理解控制器。 */
    public ContentDescriptionController(ContentServiceProperties properties, ContentDescriptionService service) {
        this.properties = properties;
        this.service = service;
    }

    /** 返回不包含 Provider 密钥的服务 readiness。 */
    @GetMapping("/ready")
    public Map<String, Object> ready(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        authorize(token);
        return service.ready();
    }

    /** 提供不需要内部令牌的进程存活探针；仅绑定在本机的 systemd 使用。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "content");
    }

    /** 按批次接收图片原始 Base64 并返回每张图片的一段综合描述。 */
    @PostMapping("/vision/describe")
    public Map<String, Object> describe(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody DescribeRequest request) {
        authorize(token);
        return service.describe(request);
    }

    private void authorize(String token) {
        if (properties.internalToken().isBlank() || token == null || !java.security.MessageDigest.isEqual(
                properties.internalToken().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "content service token is invalid");
        }
    }

    /** 内容理解请求。 */
    public record DescribeRequest(
            @JsonProperty("images") @NotEmpty @Size(max = 16) List<@Valid ImageRequest> images,
            @JsonProperty("provider") @Valid ProviderRequest provider
    ) {
        /** 创建使用服务环境变量 provider 的请求。 */
        public DescribeRequest(List<@Valid ImageRequest> images) {
            this(images, null);
        }
    }

    /** 单张图片请求；data 只在本次调用中传输，不落库。 */
    public record ImageRequest(
            @JsonProperty("image_id") @NotBlank @Size(max = 1024) String imageId,
            @JsonProperty("path") @NotBlank @Size(max = 1024) String path,
            @JsonProperty("media_type") @NotBlank @Size(max = 64) String mediaType,
            @JsonProperty("data") @NotBlank String data
    ) {
    }

    /** 主 API 在 owner 配置边界内传递给内容服务的 provider 快照。 */
    public record ProviderRequest(
            @JsonProperty("provider") @Size(max = 64) String provider,
            @JsonProperty("base_url") @Size(max = 2048) String baseUrl,
            @JsonProperty("model") @Size(max = 256) String model,
            @JsonProperty("api_key") @Size(max = 4096) String apiKey
    ) {
    }

    /** 将验证失败转换为不泄露请求正文的稳定错误。 */
    @org.springframework.web.bind.annotation.ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public Map<String, Object> validationError(Exception ignored) {
        return Map.of("ok", false, "status", 400, "code", "invalid_request", "detail", "request validation failed");
    }

    /** 将受限图片或批次错误转换为稳定的 400 响应。 */
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> invalidRequest(IllegalArgumentException error) {
        return Map.of("ok", false, "status", 400, "code", "invalid_request", "detail", safeDetail(error));
    }

    /** 将 provider 配置或协议失败转换为稳定的 503 响应。 */
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalStateException.class)
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, Object> providerUnavailable(IllegalStateException error) {
        return Map.of("ok", false, "status", 503, "code", "provider_unavailable", "detail", safeDetail(error));
    }

    private String safeDetail(Exception error) {
        String detail = error.getMessage();
        return detail == null || detail.isBlank() ? "content service request failed" : detail;
    }
}
