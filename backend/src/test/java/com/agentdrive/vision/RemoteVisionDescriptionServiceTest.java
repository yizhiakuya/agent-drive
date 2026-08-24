package com.agentdrive.vision;

import com.agentdrive.files.FileContentPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证主 API 到独立 Content Service 的 HTTP 端口契约。 */
class RemoteVisionDescriptionServiceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsOwnerProviderSnapshotAndMapsPlainDescription() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/vision/describe", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Content-Service-Token")).isEqualTo("internal");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("\"api_key\":\"owner-secret\"");
            assertThat(body).contains("\"image_id\":\"image-0\"");
            respond(exchange, 200, "{\"ok\":true,\"model\":\"vision\",\"items\":[{\"image_id\":\"image-0\",\"path\":\"x.png\",\"description\":\"粉色评论页面\"}]}");
        });
        server.start();

        UUID owner = UUID.randomUUID();
        VisionRuntimeConfig configs = ignored -> Optional.of(new VisionRuntimeConfig.Config(
                "openai_compat", "https://provider.example/v1", "vision", "owner-secret"));
        FileContentPort files = (ignored, path, maxBytes) -> new byte[]{1, 2, 3};
        RemoteVisionDescriptionService service = new RemoteVisionDescriptionService(
                "http://127.0.0.1:" + server.getAddress().getPort(), "internal",
                configs, files, new ObjectMapper());

        Map<String, Object> result = service.describeFiles(owner, List.of("x.png"));

        assertThat(result).containsEntry("ok", true);
        assertThat(result.get("items").toString()).contains("粉色评论页面");
    }

    @Test
    void rejectsInvalidServiceUrlBeforeSendingRequests() {
        VisionRuntimeConfig configs = ignored -> Optional.empty();
        FileContentPort files = (ignored, path, maxBytes) -> new byte[]{1};

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new RemoteVisionDescriptionService("http://user:pass@example.test/?x=1", "internal",
                        configs, files, new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL is invalid");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
