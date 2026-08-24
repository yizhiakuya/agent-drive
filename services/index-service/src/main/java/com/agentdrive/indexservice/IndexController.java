package com.agentdrive.indexservice;

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
import java.util.List;
import java.util.Map;

/** Index Service 内部 HTTP 契约。 */
@RestController
@RequestMapping("/internal/v1")
public final class IndexController {
    private static final String TOKEN_HEADER = "X-Index-Service-Token";
    private final IndexServiceProperties properties;
    private final IndexDocumentService service;

    /** 创建索引控制器。 */
    public IndexController(IndexServiceProperties properties, IndexDocumentService service) {
        this.properties = properties;
        this.service = service;
    }

    /** 进程存活探针。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "index");
    }

    /** 数据库/schema readiness。 */
    @GetMapping("/ready")
    public Map<String, Object> ready(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        authorize(token);
        return service.ready();
    }

    /** owner 文档迁移清单。 */
    @GetMapping("/index/manifest")
    public Map<String, Object> manifest(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                        @RequestParam("owner_id") String ownerId) {
        authorize(token);
        return service.manifest(ownerId);
    }

    /** 原子替换一个文档及其 chunks。 */
    @PostMapping("/index/documents")
    public Map<String, Object> replace(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                       @Valid @RequestBody ReplaceBody body) {
        authorize(token);
        return service.replace(new IndexDocumentService.ReplaceRequest(body.ownerId(), body.fileId(),
                body.sourceRevision(), body.documentType(), body.extractorVersion(), body.content(),
                body.chunkVersion(), body.chunks()));
    }

    /** 迁移期文本检索校验接口。 */
    @GetMapping("/index/search")
    public Map<String, Object> search(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                      @RequestParam("owner_id") String ownerId,
                                      @RequestParam("q") String query,
                                      @RequestParam(defaultValue = "20") int limit) {
        authorize(token);
        return service.search(ownerId, query, limit);
    }

    private void authorize(String token) {
        if (properties.internalToken().isBlank() || token == null || !MessageDigest.isEqual(
                properties.internalToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "index service token is invalid");
        }
    }

    /** 替换文档 JSON 请求体。 */
    public record ReplaceBody(
            @JsonProperty("owner_id") @NotBlank @Size(max = 64) String ownerId,
            @JsonProperty("file_id") @NotBlank @Size(max = 64) String fileId,
            @JsonProperty("source_revision") long sourceRevision,
            @JsonProperty("document_type") @NotBlank @Size(max = 32) String documentType,
            @JsonProperty("extractor_version") @NotBlank @Size(max = 128) String extractorVersion,
            @JsonProperty("content") @Size(max = 20_000_000) String content,
            @JsonProperty("chunk_version") @NotBlank @Size(max = 128) String chunkVersion,
            @JsonProperty("chunks") @Size(max = 16_384) List<@NotBlank String> chunks
    ) {
    }

    /** 转换索引参数错误。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> invalid(IllegalArgumentException error) {
        return Map.of("ok", false, "status", 400, "code", "invalid_request", "detail", safe(error));
    }

    /** 转换数据库/业务失败。 */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, Object> failure(RuntimeException error) {
        return Map.of("ok", false, "status", 502, "code", "index_service_failure", "detail", safe(error));
    }

    private String safe(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "index service request failed" : message;
    }
}
