package com.agentdrive.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证视觉模型目录探测的 endpoint、鉴权和脱敏边界。 */
class VisionModelClientTest {
    /**
     * 视觉模型目录应请求 OpenAI 兼容的 {@code /models}，并只返回模型 ID。
     */
    @Test
    void discoversVisionModelsWithoutReturningApiKey() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"data\":[{\"id\":\"vision-model-a\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            Map<String, Object> result = new VisionModelClient(new ObjectMapper()).listModels(
                    new VisionRuntimeConfig.Config(
                            "openai_compat",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "vision-model-a",
                            "vision-secret-value"
                    )
            );

            assertThat(result.get("ok")).isEqualTo(true);
            assertThat(result.get("models")).asList().containsExactly("vision-model-a");
            assertThat(authorization).hasValue("Bearer vision-secret-value");
            assertThat(result.toString()).doesNotContain("vision-secret-value");
        } finally {
            server.stop(0);
        }
    }

    /** 视觉描述返回一段综合文字，不要求结构化字段或独立 OCR。 */
    @Test
    void describesImageWithoutOcrField() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"一张收据的截图，白色页面中有商品和金额信息。\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            String result = new VisionModelClient(new ObjectMapper()).describe(
                    new VisionRuntimeConfig.Config(
                            "openai_compat",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "vision-model-a",
                            "vision-secret-value"),
                    new byte[]{1, 2, 3}, "image/png", "photo.png");

            assertThat(result).isEqualTo("一张收据的截图，白色页面中有商品和金额信息。");
            assertThat(requestBody).hasValueSatisfying(body -> assertThat(body)
                    .doesNotContain("content_facts")
                    .doesNotContain("text_in_image"));
        } finally {
            server.stop(0);
        }
    }

    /** Multiple images are sent in one request while each response item remains independently addressable. */
    @Test
    void describesMultipleImagesInOneRequestWithIndependentItems() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"image_id: image-0\\n一只红色杯子位于桌面中央。\\n\\nimage_id: image-1\\n一本蓝色书放在杯子旁边。\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            Map<String, String> result = new VisionModelClient(new ObjectMapper()).describeBatch(
                    new VisionRuntimeConfig.Config(
                            "openai_compat",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "vision-model-a",
                            "vision-secret-value"),
                    List.of(
                            new VisionModelClient.ImageInput("image-0", new byte[]{1, 2}, "image/png"),
                            new VisionModelClient.ImageInput("image-1", new byte[]{3, 4}, "image/png")));

            assertThat(requests).hasValue(1);
            assertThat(result).containsKeys("image-0", "image-1");
            assertThat(result.get("image-0")).isEqualTo("一只红色杯子位于桌面中央。");
            assertThat(result.get("image-1")).isEqualTo("一本蓝色书放在杯子旁边。");
            assertThat(requestBody).hasValueSatisfying(body -> assertThat(body)
                    .contains("image-0", "image-1")
                    .contains("image_url")
                    .contains("\"detail\":\"high\""));
        } finally {
            server.stop(0);
        }
    }
}
