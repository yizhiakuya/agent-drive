package com.agentdrive.api.auth;

import com.agentdrive.auth.AuthService.AuthenticationFailedException;
import com.agentdrive.auth.AuthService.InvalidCredentialException;
import com.agentdrive.auth.AuthService.InvalidDeviceException;
import com.agentdrive.auth.AuthService.InvalidPairingException;
import com.agentdrive.auth.AuthService.InvalidPasswordException;
import com.agentdrive.auth.AuthService.NotInitializedException;
import com.agentdrive.auth.AuthService.PairingLimitException;
import com.agentdrive.auth.AuthService.RateLimitExceededException;
import com.agentdrive.auth.AuthService.PasswordAlreadySetException;
import com.agentdrive.auth.ConversationSessionService.InvalidSessionIdException;
import com.agentdrive.auth.ConversationSessionService.SessionNotFoundException;
import com.agentdrive.auth.ConversationSessionService.UnauthorizedException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 将认证和会话领域异常转换为稳定的 JSON 错误响应。
 *
 * <p>该 advice 只在 {@code java-auth}/{@code java-chat} profile 生效。认证失败返回
 * 401，输入/状态不合法返回 400，限速返回 429，找不到会话返回 404；响应只暴露
 * 异常消息，不把内部堆栈或凭据写入客户端。
 */
@RestControllerAdvice
@Profile({"java-auth", "java-chat"})
public final class ChatAuthExceptionHandler {
    /**
     * 将认证失败映射为 HTTP 401。
     *
     * @param error 认证器或会话服务抛出的认证失败异常。
     * @return 仅含 {@code detail} 错误消息的 JSON 对象。
     */
    @ExceptionHandler({UnauthorizedException.class, AuthenticationFailedException.class,
            InvalidCredentialException.class})
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> unauthorized(RuntimeException error) {
        return Map.of("detail", error.getMessage());
    }

    /**
     * 将密码、设备、配对码和会话标识等业务输入错误映射为 HTTP 400。
     *
     * @param error 描述具体业务校验失败原因的异常。
     * @return 仅含 {@code detail} 错误消息的 JSON 对象。
     */
    @ExceptionHandler({InvalidSessionIdException.class, InvalidPasswordException.class,
            InvalidDeviceException.class, InvalidPairingException.class,
            NotInitializedException.class, PairingLimitException.class,
            PasswordAlreadySetException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(RuntimeException error) {
        return Map.of("detail", error.getMessage());
    }

    /**
     * 将认证尝试超过客户端限额映射为 HTTP 429。
     *
     * @param error 限速器抛出的异常，消息包含客户端可理解的重试提示。
     * @return 仅含 {@code detail} 错误消息的 JSON 对象。
     */
    @ExceptionHandler(RateLimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Map<String, String> rateLimited(RateLimitExceededException error) {
        return Map.of("detail", error.getMessage());
    }

    /**
     * 将查询或删除不存在会话的异常映射为 HTTP 404。
     *
     * @param error 会话服务报告的缺失会话异常。
     * @return 仅含 {@code detail} 错误消息的 JSON 对象。
     */
    @ExceptionHandler(SessionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> missingSession(SessionNotFoundException error) {
        return Map.of("detail", error.getMessage());
    }
}
