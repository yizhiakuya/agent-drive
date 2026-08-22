package com.agentdrive.api;

import com.agentdrive.agent.ChatLogSupport;
import com.agentdrive.vision.VisionProviderUnavailableException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.bind.support.WebExchangeBindException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 统一处理未被领域 advice 消费的 WebFlux 业务异常。
 *
 * <p>所有 API 错误都提供稳定的 {@code status/code/detail} 字段；detail 只使用
 * 脱敏后的异常消息，不把堆栈、凭据或 provider 原始响应泄露给客户端。更具体的
 * controller advice（认证、文件、配置、Skill）优先处理自己的领域异常。</p>
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public final class ApiExceptionHandler {
    /** Missing static/API resource must remain an actual HTTP 404, never an HTTP 200 error body. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> missingResource(NoResourceFoundException error) {
        return response(HttpStatus.NOT_FOUND.value(), "not_found", "资源不存在");
    }

    /** Spring 已明确指定状态的异常。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> responseStatus(ResponseStatusException error) {
        HttpStatusCode status = error.getStatusCode();
        String detail = safeDetail(error.getReason(), "请求无法完成");
        return response(status.value(), codeFor(status.value()), detail);
    }

    /** Bean validation 和 JSON 输入无法绑定时返回字段级原因。 */
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<Map<String, Object>> validation(WebExchangeBindException error) {
        String detail = error.getFieldErrors().stream()
                .map(field -> field.getField() + ": " + safeDetail(field.getDefaultMessage(), "值无效"))
                .distinct()
                .collect(Collectors.joining("; "));
        return response(HttpStatus.BAD_REQUEST.value(), "validation_failed",
                detail.isBlank() ? "请求参数校验失败" : detail);
    }

    /** 请求体格式、参数类型或缺失输入错误。 */
    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<Map<String, Object>> input(ServerWebInputException error) {
        return response(HttpStatus.BAD_REQUEST.value(), "invalid_request",
                safeDetail(error.getReason(), "请求参数或请求体无效"));
    }

    /** 领域层未包装的参数异常。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> argument(IllegalArgumentException error) {
        return response(HttpStatus.BAD_REQUEST.value(), "invalid_argument", safeDetail(error.getMessage(), "参数无效"));
    }

    /** 视觉 provider 在入队前置检查失败，提示用户修正配置而不是重试空队列。 */
    @ExceptionHandler(VisionProviderUnavailableException.class)
    public ResponseEntity<Map<String, Object>> visionProvider(VisionProviderUnavailableException error) {
        return response(HttpStatus.SERVICE_UNAVAILABLE.value(), "vision_provider_unavailable",
                safeDetail(error.getMessage(), "视觉模型当前不可用"));
    }

    /** 未分类运行时异常的统一安全兜底。 */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> unexpected(RuntimeException error) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR.value(), "internal_error",
                safeDetail(ChatLogSupport.message(error), "业务处理失败，请稍后重试"));
    }

    private ResponseEntity<Map<String, Object>> response(int status, String code, String detail) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("code", code);
        result.put("detail", detail);
        result.put("ok", false);
        return ResponseEntity.status(status).body(result);
    }

    private String safeDetail(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String detail = ChatLogSupport.message(new IllegalStateException(value));
        return detail.isBlank() ? fallback : detail;
    }

    private String codeFor(int status) {
        return switch (status) {
            case 400 -> "bad_request";
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not_found";
            case 409 -> "conflict";
            case 429 -> "rate_limited";
            case 503 -> "service_unavailable";
            default -> status >= 500 ? "internal_error" : "http_error";
        };
    }
}
