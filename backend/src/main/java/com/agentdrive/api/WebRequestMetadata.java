package com.agentdrive.api;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 解析可安全复用于日志和响应头的请求元数据。
 */
public final class WebRequestMetadata {
    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    private static final String REQUEST_ID_ATTRIBUTE = WebRequestMetadata.class.getName() + ".requestId";
    private static final String[] CORRELATION_HEADERS = {
            REQUEST_ID_HEADER, "X-Correlation-ID", "traceparent"
    };
    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private WebRequestMetadata() {
    }

    /**
     * 复用通过格式校验的关联 ID；不存在时为当前请求生成 UUID。
     */
    public static String requestId(ServerWebExchange exchange) {
        Object existing = exchange.getAttribute(REQUEST_ID_ATTRIBUTE);
        if (existing instanceof String value) {
            return value;
        }
        for (String header : CORRELATION_HEADERS) {
            String candidate = exchange.getRequest().getHeaders().getFirst(header);
            if (candidate != null && SAFE_CORRELATION_ID.matcher(candidate).matches()) {
                exchange.getAttributes().put(REQUEST_ID_ATTRIBUTE, candidate);
                return candidate;
            }
        }
        String generated = UUID.randomUUID().toString();
        exchange.getAttributes().put(REQUEST_ID_ATTRIBUTE, generated);
        return generated;
    }

    /**
     * 返回匹配后的路由模板，避免暴露 query value 和实际路径参数。
     */
    public static String routeTemplate(ServerWebExchange exchange) {
        Object pattern = exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? "/api/**" : pattern.toString();
    }

    /**
     * 仅当 TCP 对端为 loopback 时信任 nginx 覆写的单值合法 IP。
     */
    public static String clientAddress(ServerHttpRequest request) {
        return clientAddress(request.getRemoteAddress(), request.getHeaders().getFirst("X-Forwarded-For"));
    }

    static String clientAddress(InetSocketAddress remoteAddress, String forwardedFor) {
        InetAddress remote = remoteAddress == null ? null : remoteAddress.getAddress();
        String fallback = remote == null ? "unknown" : remote.getHostAddress();
        if (remote == null || !remote.isLoopbackAddress()) {
            return fallback;
        }
        String candidate = forwardedFor == null ? "" : forwardedFor.trim();
        if (candidate.isEmpty() || candidate.indexOf(',') >= 0 || !isIpLiteral(candidate)) {
            return fallback;
        }
        return candidate;
    }

    private static boolean isIpLiteral(String value) {
        if (value.indexOf(':') >= 0) {
            if (!value.matches("[0-9A-Fa-f:.]+")) {
                return false;
            }
            try {
                return InetAddress.getByName(value) instanceof Inet6Address;
            } catch (UnknownHostException ignored) {
                return false;
            }
        }
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3 || !octet.chars().allMatch(Character::isDigit)) {
                return false;
            }
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }
}
