package com.agentdrive.api.config;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 将 Provider 配置校验失败转换为 HTTP 400 的 {@code detail} JSON 响应。
 */
@RestControllerAdvice(assignableTypes = ProviderConfigController.class)
@Profile({"java-auth", "java-chat"})
public final class ProviderConfigExceptionHandler {
    /**
     * 返回配置规范化、URL 校验或 Provider 类型校验失败的客户端错误。
     *
     * @param error 配置控制器抛出的参数异常。
     * @return HTTP 400 和非空 {@code detail} 消息。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException error) {
        return Map.of("detail", error.getMessage() == null ? "invalid provider configuration" : error.getMessage());
    }
}
