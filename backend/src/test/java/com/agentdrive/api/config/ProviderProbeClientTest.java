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
            assertThat(result.capabilities()).containsEntry("model-a", false);
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

    @Test
    void returnsProviderCapabilityHintsBeforeModelNameFallback() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] body = ("{\"data\":["
                    + "{\"id\":\"model-with-image\",\"modalities\":[\"text\",\"image\"]},"
                    + "{\"id\":\"model-text-only\",\"capabilities\":{\"vision\":false}},"
                    + "{\"id\":\"gpt-4o-mini\",\"modalities\":[\"text\"]},"
                    + "{\"id\":\"unknown-model\"}]}" ).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            ProviderProbeClient.ProbeResult result = new ProviderProbeClient(new ObjectMapper())
                    .listModels("openai_compat", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "secret");

            assertThat(result.models()).containsExactly(
                    "model-with-image", "model-text-only", "gpt-4o-mini", "unknown-model");
            assertThat(result.capabilities()).containsEntry("model-with-image", true)
                    .containsEntry("model-text-only", false)
                    .containsEntry("gpt-4o-mini", false)
                    .containsEntry("unknown-model", false);
            assertThat(result.asMap()).containsKey("model_capabilities");
        } finally {
            server.stop(0);
        }
    }
}
