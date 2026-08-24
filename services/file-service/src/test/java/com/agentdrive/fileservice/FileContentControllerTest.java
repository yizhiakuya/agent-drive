package com.agentdrive.fileservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/** 验证 File Service 内部 token、health 和错误 envelope。 */
class FileContentControllerTest {
    @Test
    void healthIsPublicAndReadRequiresToken(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        UUID owner = UUID.randomUUID();
        Path file = root.resolve(owner.toString()).resolve("a.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "hello");
        FileServiceProperties properties = new FileServiceProperties("internal", root.toString(), 1024L);
        FileContentService service = new FileContentService(properties);
        WebTestClient client = WebTestClient.bindToController(new FileContentController(properties, service)).build();

        client.get().uri("/internal/v1/health").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("UP");
        client.get().uri("/internal/v1/ready").exchange().expectStatus().isUnauthorized();
        client.post().uri("/internal/v1/files/content")
                .header("X-File-Service-Token", "internal")
                .bodyValue(Map.of("owner_id", owner.toString(), "path", "a.txt"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data").isEqualTo("aGVsbG8=");
    }
}
