package com.agentdrive.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 为前端 SPA 的无扩展名 GET 深链接改写资源路径。
 *
 * <p>API 路径和看起来像静态文件的路径原样交给后续过滤器，避免把 API 404
 * 或缺失资源错误改写成前端页面；其余 GET 请求改写为 {@code /index.html}。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class FrontendSpaFallbackWebFilter implements WebFilter {
    /**
     * 根据请求路径选择原样放行或执行 SPA 回退。
     *
     * @param exchange 当前请求及其可变请求上下文。
     * @param chain 后续 WebFilter 链；改写后的请求仍由同一条链继续处理。
     * @return 后续过滤器完成时结束的异步信号。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (!HttpMethod.GET.equals(exchange.getRequest().getMethod())
                || isApiPath(path)
                || hasFileExtension(path)) {
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest().mutate().path("/index.html").build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    /**
     * 判断路径是否属于 API 命名空间。
     *
     * @param path 应用内的请求路径。
     * @return 路径等于 {@code /api} 或以 {@code /api/} 开头时返回 {@code true}。
     */
    private boolean isApiPath(String path) {
        return "/api".equals(path) || path.startsWith("/api/");
    }

    /**
     * 判断路径最后一个片段是否包含文件扩展名。
     *
     * @param path 应用内的请求路径。
     * @return 最后一个斜杠后的片段含点号时返回 {@code true}。
     */
    private boolean hasFileExtension(String path) {
        int slash = path.lastIndexOf('/');
        return path.substring(slash + 1).contains(".");
    }
}
