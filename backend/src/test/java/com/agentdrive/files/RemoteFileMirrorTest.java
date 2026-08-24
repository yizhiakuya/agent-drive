package com.agentdrive.files;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证主 API 写入 File Service 镜像的 HTTP 契约。 */
class RemoteFileMirrorTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsRevisionMd5AndRawData() throws Exception {
        UUID owner = UUID.randomUUID();
        byte[] bytes = "mirror".getBytes(StandardCharsets.UTF_8);
        String md5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/files/mirror", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("PUT");
            assertThat(exchange.getRequestHeaders().getFirst("X-File-Service-Token")).isEqualTo("internal");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains(owner.toString()).contains("\"revision\":4")
                    .contains(md5);
            respond(exchange, 200, "{\"ok\":true,\"path\":\"docs/a.txt\"}");
        });
        server.start();

        RemoteFileMirror mirror = new RemoteFileMirror(
                "http://127.0.0.1:" + server.getAddress().getPort(), "internal", new ObjectMapper());
        mirror.syncFile(owner, "docs/a.txt", 4, bytes, md5);
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
