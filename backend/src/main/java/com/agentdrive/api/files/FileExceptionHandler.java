package com.agentdrive.api.files;

import com.agentdrive.files.FileStorageException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 将文件存储领域异常转换为对应 HTTP 状态和 {@code detail} JSON。
 *
 * <p>异常本身携带稳定的 HTTP 状态，控制器因此无需把路径越界、冲突和内部存储
 * 故障重新分类；响应不暴露 Java 堆栈。
 */
@RestControllerAdvice(assignableTypes = FileController.class)
@Profile({"java-files", "java-auth", "java-chat"})
public final class FileExceptionHandler {
    /**
     * 使用存储异常携带的状态码生成错误响应。
     *
     * @param error 文件存储服务抛出的、包含稳定状态码和客户端消息的异常。
     * @return 状态码与异常一致、正文仅含 {@code detail} 的响应。
     */
    @ExceptionHandler(FileStorageException.class)
    public ResponseEntity<Map<String, Object>> storage(FileStorageException error) {
        int status = error.status();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("status", status);
        body.put("code", codeFor(status));
        body.put("detail", error.getMessage());
        body.put("ok", false);
        return ResponseEntity.status(status).body(body);
    }

    private String codeFor(int status) {
        return switch (status) {
            case 400 -> "bad_request";
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not_found";
            case 409 -> "conflict";
            case 413 -> "payload_too_large";
            case 429 -> "rate_limited";
            case 503 -> "service_unavailable";
            default -> status >= HttpStatus.INTERNAL_SERVER_ERROR.value() ? "internal_error" : "http_error";
        };
    }
}
