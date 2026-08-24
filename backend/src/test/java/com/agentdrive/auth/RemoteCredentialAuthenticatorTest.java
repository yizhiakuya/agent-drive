package com.agentdrive.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 API 对 Identity Service introspection 响应的解析。 */
class RemoteCredentialAuthenticatorTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void mapsOwnerAndCredentialKindWithoutReturningToken() throws IOException {
        UUID owner = UUID.randomUUID();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/introspect", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Identity-Service-Token")).isEqualTo("internal");
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("\"credential\":\"session-secret\"");
            respond(exchange, 200, "{\"authenticated\":true,\"owner_id\":\"" + owner
                    + "\",\"kind\":\"SESSION\"}");
        });
        server.start();

        RemoteCredentialAuthenticator authenticator = new RemoteCredentialAuthenticator(
                "http://127.0.0.1:" + server.getAddress().getPort(), "internal", new ObjectMapper());

        assertThat(authenticator.authenticate("session-secret"))
                .contains(new AuthenticatedPrincipal(owner, AuthenticatedPrincipal.CredentialKind.SESSION));
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
