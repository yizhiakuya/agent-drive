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

    @Test
    void sendsMovePathMutation() throws IOException {
        UUID owner = UUID.randomUUID();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/files/mirror/move", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("X-File-Service-Token")).isEqualTo("internal");
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .contains(owner.toString()).contains("source.txt").contains("moved.txt");
            respond(exchange, 200, "{\"ok\":true}");
        });
        server.start();

        RemoteFileMirror mirror = new RemoteFileMirror(
                "http://127.0.0.1:" + server.getAddress().getPort(), "internal", new ObjectMapper());
        mirror.movePath(owner, "source.txt", "moved.txt", false);
    }

    @Test
    void sendsTreeDeleteMutation() throws IOException {
        UUID owner = UUID.randomUUID();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/files/mirror/tree", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("DELETE");
            assertThat(exchange.getRequestURI().getQuery()).contains(owner.toString()).contains("folder");
            respond(exchange, 200, "{\"ok\":true}");
        });
        server.start();

        RemoteFileMirror mirror = new RemoteFileMirror(
                "http://127.0.0.1:" + server.getAddress().getPort(), "internal", new ObjectMapper());
        mirror.deletePath(owner, "folder");
    }

    @Test
    void sendsDirectoryMirrorAndReturnsCreatedFlag() throws IOException {
        UUID owner = UUID.randomUUID();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/files/mirror/directory", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("PUT");
            assertThat(exchange.getRequestHeaders().getFirst("X-File-Service-Token")).isEqualTo("internal");
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .contains(owner.toString()).contains("nested/dir");
            respond(exchange, 200, "{\"ok\":true,\"created\":true}");
        });
        server.start();

        RemoteFileMirror mirror = new RemoteFileMirror(
                "http://127.0.0.1:" + server.getAddress().getPort(), "internal", new ObjectMapper());
        assertThat(mirror.mkdirPath(owner, "nested/dir")).isTrue();
    }

    @Test
    void sendsTrashAndRestoreMutations() throws IOException {
        UUID owner = UUID.randomUUID();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/files/mirror/trash", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            respond(exchange, 200, "{\"ok\":true}");
        });
        server.createContext("/internal/v1/files/mirror/restore", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            respond(exchange, 200, "{\"ok\":true}");
        });
        server.start();

        RemoteFileMirror mirror = new RemoteFileMirror(
                "http://127.0.0.1:" + server.getAddress().getPort(), "internal", new ObjectMapper());
        mirror.trashPath(owner, "a.txt", UUID.randomUUID().toString());
        mirror.restorePath(owner, UUID.randomUUID().toString(), "a.txt");
    }

    @Test
    void sendsCommittedTrashCleanup() throws IOException {
        UUID owner = UUID.randomUUID();
        String trashId = UUID.randomUUID().toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/files/mirror/trash", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("DELETE");
            assertThat(exchange.getRequestURI().getQuery()).contains(owner.toString()).contains(trashId);
            respond(exchange, 200, "{\"ok\":true}");
        });
        server.start();

        RemoteFileMirror mirror = new RemoteFileMirror(
                "http://127.0.0.1:" + server.getAddress().getPort(), "internal", new ObjectMapper());
        mirror.emptyTrash(owner, trashId);
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
