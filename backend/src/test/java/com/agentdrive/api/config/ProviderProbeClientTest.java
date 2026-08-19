package com.agentdrive.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderProbeClientTest {
    @Test
    void discoversOpenAiModelsWithoutReturningApiKey() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"data\":[{\"id\":\"model-a\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            ProviderProbeClient.ProbeResult result = new ProviderProbeClient(new ObjectMapper())
                    .listModels("openai_compat", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "sk-secret-value");

            assertThat(result.ok()).isTrue();
            assertThat(result.models()).containsExactly("model-a");
            assertThat(authorization).hasValue("Bearer sk-secret-value");
            assertThat(result.asMap().toString()).doesNotContain("sk-secret-value");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonHttpProviderUrlsBeforeConnecting() {
        ProviderProbeClient.ProbeResult result = new ProviderProbeClient(new ObjectMapper())
                .listModels("openai_compat", "file:///etc/passwd", "secret");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("http(s)");
    }
}
