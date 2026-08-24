package com.agentdrive.fileservice;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** File Service 内部内容读取和健康探针接口。 */
@RestController
@RequestMapping("/internal/v1")
public final class FileContentController {
    private static final String TOKEN_HEADER = "X-File-Service-Token";
    private final FileServiceProperties properties;
    private final FileContentService service;

    /** 创建内部文件接口。 */
    public FileContentController(FileServiceProperties properties, FileContentService service) {
        this.properties = properties;
        this.service = service;
    }

    /** 返回进程存活状态，不需要内部令牌。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "file");
    }

    /** 返回文件服务 readiness。 */
    @GetMapping("/ready")
    public Map<String, Object> ready(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        authorize(token);
        return service.ready();
    }

    /** 读取 owner 文件原始 bytes。 */
    @PostMapping("/files/content")
    public Map<String, Object> read(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                    @Valid @RequestBody ReadBody request) {
        authorize(token);
        return service.read(new FileContentService.ReadRequest(request.ownerId(), request.path(), request.maxBytes()));
    }

    /** 返回 owner 可见文件的迁移校验清单。 */
    @GetMapping("/files/manifest")
    public Map<String, Object> manifest(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                        @RequestParam("owner_id") String ownerId) {
        authorize(token);
        return service.manifest(ownerId);
    }

    private void authorize(String token) {
        if (properties.internalToken().isBlank() || token == null || !MessageDigest.isEqual(
                properties.internalToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "file service token is invalid");
        }
    }

    /** 接口 JSON 请求体。 */
    public record ReadBody(
            @JsonProperty("owner_id") @NotBlank @Size(max = 64) String ownerId,
            @JsonProperty("path") @NotBlank @Size(max = 2048) String path,
            @JsonProperty("max_bytes") Long maxBytes
    ) {
    }

    /** 将路径或 owner 参数错误转换为稳定 400 响应。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> invalidRequest(IllegalArgumentException error) {
        return Map.of("ok", false, "status", 400, "code", "invalid_request",
                "detail", safeDetail(error));
    }

    /** 将本地读取失败转换为稳定 502 响应。 */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, Object> readFailure(IllegalStateException error) {
        return Map.of("ok", false, "status", 502, "code", "file_service_failure",
                "detail", safeDetail(error));
    }

    private String safeDetail(Exception error) {
        String detail = error.getMessage();
        return detail == null || detail.isBlank() ? "file service request failed" : detail;
    }
}
