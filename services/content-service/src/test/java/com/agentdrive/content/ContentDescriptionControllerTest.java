package com.agentdrive.content;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证内容服务的内部认证和健康探针契约。 */
class ContentDescriptionControllerTest {
    @Test
    void healthIsPublicButReadyRequiresInternalToken() {
        ContentServiceProperties properties = new ContentServiceProperties(
                "internal", "openai_compat", "", "", "", 4, 1024L, 2048, 8192L);
        ContentDescriptionService service = new ContentDescriptionService(properties, new com.fasterxml.jackson.databind.ObjectMapper());
        WebTestClient client = WebTestClient.bindToController(
                        new ContentHealthController(),
                        new ContentDescriptionController(properties, service))
                .build();

        client.get().uri("/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
        client.get().uri("/internal/v1/ready")
                .exchange()
                .expectStatus().isUnauthorized();
        client.get().uri("/internal/v1/ready")
                .header("X-Content-Service-Token", "internal")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.ready").isEqualTo(true);
    }

    @Test
    void invalidImageRequestReturnsStableClientError() {
        ContentServiceProperties properties = new ContentServiceProperties(
                "internal", "openai_compat", "", "", "", 4, 1024L, 2048, 8192L);
        ContentDescriptionService service = new ContentDescriptionService(properties, new com.fasterxml.jackson.databind.ObjectMapper());
        WebTestClient client = WebTestClient.bindToController(
                        new ContentHealthController(),
                        new ContentDescriptionController(properties, service))
                .build();

        Map<String, Object> request = Map.of(
                "images", java.util.List.of(Map.of(
                        "image_id", "image-0", "path", "x.png", "media_type", "image/png", "data", "%%%")));
        client.post().uri("/internal/v1/vision/describe")
                .header("X-Content-Service-Token", "internal")
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_request")
                .jsonPath("$.detail").isEqualTo("image data is not valid Base64");
    }
}
