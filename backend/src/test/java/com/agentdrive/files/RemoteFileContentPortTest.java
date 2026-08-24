package com.agentdrive.files;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证主 API 读取独立 File Service 的响应校验和安全边界。 */
class RemoteFileContentPortTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void validatesOwnerPathSizeAndMd5() throws IOException {
        UUID owner = UUID.randomUUID();
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        String data = Base64.getEncoder().encodeToString(bytes);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/files/content", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-File-Service-Token")).isEqualTo("internal");
            respond(exchange, 200, "{\"ok\":true,\"owner_id\":\"" + owner
                    + "\",\"path\":\"a.txt\",\"size_bytes\":5,\"content_md5\":\"5d41402abc4b2a76b9719d911017c592\",\"data\":\""
                    + data + "\"}");
        });
        server.start();

        RemoteFileContentPort port = new RemoteFileContentPort(
                "http://127.0.0.1:" + server.getAddress().getPort(), "internal", new ObjectMapper());

        assertThat(port.readBytes(owner, "a.txt", 100L)).containsExactly(bytes);
    }

    @Test
    void rejectsInvalidServiceUrl() {
        assertThatThrownBy(() -> new RemoteFileContentPort(
                "https://user:pass@example.test/?x=1", "internal", new ObjectMapper()))
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
