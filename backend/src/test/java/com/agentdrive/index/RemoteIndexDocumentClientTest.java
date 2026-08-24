package com.agentdrive.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Index Service 迁移客户端的 token、revision 和文档契约。 */
class RemoteIndexDocumentClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void replacesDocumentWithOwnerAndRevision() throws IOException {
        UUID owner = UUID.randomUUID();
        UUID file = UUID.randomUUID();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/index/documents", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Index-Service-Token")).isEqualTo("internal");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains(owner.toString()).contains(file.toString())
                    .contains("\"source_revision\":3");
            respond(exchange, 200, "{\"ok\":true,\"chunk_count\":1}");
        });
        server.start();

        RemoteIndexDocumentClient client = new RemoteIndexDocumentClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), "internal", new ObjectMapper());
        var result = client.replace(owner, file, 3, "vision", "vision-description-v3",
                "粉色评论页面", "vision-chunk-v3", List.of("粉色评论页面"));

        assertThat(result).containsEntry("ok", true).containsEntry("chunk_count", 1);
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
