package com.agentdrive.api.config;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 将视觉配置输入、地址和 provider 校验错误转换为稳定的 HTTP 400 JSON。 */
@RestControllerAdvice(assignableTypes = VisionConfigController.class)
@Profile({"java-auth", "java-chat"})
public final class VisionConfigExceptionHandler {
    /**
     * 返回视觉配置参数错误。
     * @param error 配置服务抛出的参数异常。
     * @return HTTP 400 和 detail 文本。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException error) {
        return Map.of("detail", error.getMessage() == null ? "invalid vision configuration" : error.getMessage());
    }
}
