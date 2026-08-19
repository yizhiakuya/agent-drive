package com.agentdrive.api.devices;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 将设备控制器报告的设备缺失转换为 JSON 404 响应。
 */
@RestControllerAdvice(assignableTypes = DeviceController.class)
@Profile({"java-auth", "java-chat"})
public final class DeviceExceptionHandler {
    /**
     * 响应设备不存在或不属于当前用户的错误。
     *
     * @param error 控制器抛出的设备缺失异常。
     * @return 仅含 {@code detail} 字段、HTTP 状态为 404 的错误对象。
     */
    @ExceptionHandler(DeviceController.DeviceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> missing(DeviceController.DeviceNotFoundException error) {
        return Map.of("detail", error.getMessage());
    }
}
