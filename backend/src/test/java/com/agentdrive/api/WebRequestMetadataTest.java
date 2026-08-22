package com.agentdrive.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class WebRequestMetadataTest {
    @Test
    void forwardedClientAddressIsTrustedOnlyFromTheLoopbackProxy() {
        assertThat(WebRequestMetadata.clientAddress(
                new InetSocketAddress("127.0.0.1", 8000), "203.0.113.9"))
                .isEqualTo("203.0.113.9");
        assertThat(WebRequestMetadata.clientAddress(
                new InetSocketAddress("198.51.100.4", 8000), "203.0.113.9"))
                .isEqualTo("198.51.100.4");
        assertThat(WebRequestMetadata.clientAddress(
                new InetSocketAddress("127.0.0.1", 8000), "203.0.113.9, 198.51.100.4"))
                .isEqualTo("127.0.0.1");
        assertThat(WebRequestMetadata.clientAddress(
                new InetSocketAddress("127.0.0.1", 8000), "not-an-ip"))
                .isEqualTo("127.0.0.1");
    }

    @Test
    void requestIdReusesOnlySafeHeadersAndRemainsStableWithinTheRequest() {
        MockServerWebExchange trusted = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/health")
                        .header("X-Correlation-ID", "request-123")
                        .build()
        );
        assertThat(WebRequestMetadata.requestId(trusted)).isEqualTo("request-123");
        assertThat(WebRequestMetadata.requestId(trusted)).isEqualTo("request-123");

        MockServerWebExchange unsafe = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/health")
                        .header(WebRequestMetadata.REQUEST_ID_HEADER, "bad value")
                        .build()
        );
        assertThat(WebRequestMetadata.requestId(unsafe))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
