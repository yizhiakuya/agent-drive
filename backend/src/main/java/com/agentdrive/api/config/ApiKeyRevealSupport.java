package com.agentdrive.api.config;

import com.agentdrive.auth.AuthenticatedPrincipal;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

/** 统一保护设置页完整 API key 回显的认证、缓存和错误边界。 */
final class ApiKeyRevealSupport {
    private ApiKeyRevealSupport() {
    }

    /**
     * 限制完整 key 回显只能由网页登录会话触发。
     *
     * @param principal 当前认证主体。
     * @throws ResponseStatusException 设备令牌请求产生 403。
     */
    static void requireSession(AuthenticatedPrincipal principal) {
        if (principal.credentialKind() != AuthenticatedPrincipal.CredentialKind.SESSION) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "web session required to reveal API key");
        }
    }

    /**
     * 标记明文 key 响应不得被浏览器或中间代理缓存。
     *
     * @param exchange 当前 Web 请求上下文。
     */
    static void markNoStore(ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        exchange.getResponse().getHeaders().set(HttpHeaders.PRAGMA, "no-cache");
        exchange.getResponse().getHeaders().setExpires(0);
    }

    /**
     * 创建不包含任何密钥内容的统一未配置异常。
     *
     * @return HTTP 404 异常。
     */
    static ResponseStatusException missingSavedKey() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "saved API key not found");
    }
}
