package com.agentdrive.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 为每个 API 请求输出一条不含凭据的完成日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class ApiRequestLoggingWebFilter implements WebFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiRequestLoggingWebFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (!isApiPath(path)) {
            return chain.filter(exchange);
        }

        String requestId = WebRequestMetadata.requestId(exchange);
        exchange.getResponse().getHeaders().set(WebRequestMetadata.REQUEST_ID_HEADER, requestId);
        String method = exchange.getRequest().getMethod().name();
        String clientAddress = WebRequestMetadata.clientAddress(exchange.getRequest());
        long startedAt = System.nanoTime();
        AtomicBoolean failed = new AtomicBoolean();

        return chain.filter(exchange)
                .doOnError(ignored -> failed.set(true))
                .doFinally(signal -> logCompletion(
                        exchange, requestId, method, clientAddress, startedAt, failed.get(), signal));
    }

    private void logCompletion(ServerWebExchange exchange,
                               String requestId,
                               String method,
                               String clientAddress,
                               long startedAt,
                               boolean failed,
                               SignalType signal) {
        HttpStatusCode responseStatus = exchange.getResponse().getStatusCode();
        int status = responseStatus != null
                ? responseStatus.value()
                : signal == SignalType.CANCEL ? 499 : failed ? 500 : 200;
        long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        String terminal = signal == SignalType.CANCEL ? "cancel" : failed ? "error" : "complete";
        LOGGER.info(
                "api_request_completed request_id={} method={} route={} status={} duration_ms={} client_ip={} terminal={}",
                requestId,
                method,
                WebRequestMetadata.routeTemplate(exchange),
                status,
                durationMillis,
                clientAddress,
                terminal
        );
    }

    private boolean isApiPath(String path) {
        return "/api".equals(path) || path.startsWith("/api/");
    }
}
