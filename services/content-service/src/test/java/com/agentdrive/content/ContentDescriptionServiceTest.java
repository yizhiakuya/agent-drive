package com.agentdrive.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证内容服务 readiness 和请求上限边界。 */
class ContentDescriptionServiceTest {
    private HttpServer provider;

    @AfterEach
    void stopProvider() {
        if (provider != null) provider.stop(0);
    }

    @Test
    void readinessDoesNotExposeProviderSecret() {
        ContentServiceProperties properties = new ContentServiceProperties(
                "internal", "openai_compat", "http://127.0.0.1:1/v1", "vision", "secret", 4, 1024L, 2048);
        Map<String, Object> ready = new ContentDescriptionService(properties, new ObjectMapper()).ready();
        assertThat(ready).containsEntry("ready", true).doesNotContainValue("secret");
    }

    @Test
    void acceptsBase64ImageRequestShapeWithoutPersistingBytes() {
        ContentServiceProperties properties = new ContentServiceProperties(
                "internal", "openai_compat", "http://127.0.0.1:1/v1", "vision", "secret", 4, 1024L, 2048);
        String data = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        ContentDescriptionController.DescribeRequest request = new ContentDescriptionController.DescribeRequest(
                List.of(new ContentDescriptionController.ImageRequest("image-0", "x.png", "image/png", data)));
        assertThat(request.images()).hasSize(1);
        assertThat(data).isNotBlank();
    }

    @Test
    void sendsHighDetailOriginalImageToProviderAndReturnsPlainDescription() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"image_id: image-0\\n粉色主题的评论页面，顶部有继续播放按钮，底部有多条中文评论。\"}}]}");
        });
        provider.start();
        String data = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
        ContentServiceProperties properties = new ContentServiceProperties(
                "internal", "openai_compat", "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1",
                "vision", "secret", 4, 1024L, 2048, 8192L);
        ContentDescriptionController.DescribeRequest request = new ContentDescriptionController.DescribeRequest(
                List.of(new ContentDescriptionController.ImageRequest("image-0", "x.png", "image/png", data)),
                new ContentDescriptionController.ProviderRequest(null, null, null, null));

        Map<String, Object> result = new ContentDescriptionService(properties, new ObjectMapper()).describe(request);

        assertThat(result).containsEntry("ok", true);
        assertThat(result.get("items").toString()).contains("粉色主题的评论页面");
        assertThat(requestBody).hasValueSatisfying(body -> {
            assertThat(body).contains("\"detail\":\"high\"");
            assertThat(body).contains("data:image/png;base64," + data);
            assertThat(body).contains("\"model\":\"vision\"");
        });
    }

    @Test
    void acceptsSegmentedProviderContent() throws IOException {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/chat/completions", exchange ->
                respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"image_id: image-0\\n\"},{\"type\":\"text\",\"text\":\"蓝紫色抽象图标。\"}]}}]}"));
        provider.start();
        String data = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        ContentServiceProperties properties = new ContentServiceProperties(
                "internal", "openai_compat", "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1",
                "vision", "secret", 4, 1024L, 2048, 8192L);
        ContentDescriptionController.DescribeRequest request = new ContentDescriptionController.DescribeRequest(
                List.of(new ContentDescriptionController.ImageRequest("image-0", "x.png", "image/png", data)));

        Map<String, Object> result = new ContentDescriptionService(properties, new ObjectMapper()).describe(request);

        assertThat(result).containsEntry("ok", true);
        assertThat(result.get("items").toString()).contains("蓝紫色抽象图标");
    }

    @Test
    void rejectsProviderUrlWithQueryOrCredentials() {
        ContentServiceProperties properties = new ContentServiceProperties(
                "internal", "openai_compat", "https://user:pass@example.test/v1?leak=1",
                "vision", "secret", 4, 1024L, 2048, 8192L);
        String data = Base64.getEncoder().encodeToString(new byte[]{1});
        ContentDescriptionController.DescribeRequest request = new ContentDescriptionController.DescribeRequest(
                List.of(new ContentDescriptionController.ImageRequest("image-0", "x.png", "image/png", data)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new ContentDescriptionService(properties, new ObjectMapper()).describe(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base_url is invalid");
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
