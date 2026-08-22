package com.agentdrive.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.HandlerMapping;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ApiRequestLoggingWebFilterTest {
    @Test
    void logsSanitizedApiCompletionAndPropagatesRequestId(CapturedOutput output) {
        ApiRequestLoggingWebFilter filter = new ApiRequestLoggingWebFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/files?token=do-not-log")
                        .header(WebRequestMetadata.REQUEST_ID_HEADER, "request-123")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer do-not-log-either")
                        .header("X-Forwarded-For", "203.0.113.9")
                        .remoteAddress(new InetSocketAddress("127.0.0.1", 8080))
                        .build()
        );

        filter.filter(exchange, current -> {
            current.getAttributes().put(
                    HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/files");
            current.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
            return Mono.empty();
        }).block();

        assertThat(exchange.getResponse().getHeaders().getFirst(WebRequestMetadata.REQUEST_ID_HEADER))
                .isEqualTo("request-123");
        assertThat(output.getOut())
                .contains("api_request_completed request_id=request-123 method=GET route=/api/v1/files status=204")
                .contains("client_ip=203.0.113.9 terminal=complete")
                .doesNotContain("do-not-log")
                .doesNotContain("Authorization");
    }

    @Test
    void ignoresNonApiAssets(CapturedOutput output) {
        ApiRequestLoggingWebFilter filter = new ApiRequestLoggingWebFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/_next/static/app.js").build());

        filter.filter(exchange, current -> Mono.empty()).block();

        assertThat(exchange.getResponse().getHeaders()).doesNotContainKey(WebRequestMetadata.REQUEST_ID_HEADER);
        assertThat(output.getOut()).doesNotContain("api_request_completed");
    }
}
