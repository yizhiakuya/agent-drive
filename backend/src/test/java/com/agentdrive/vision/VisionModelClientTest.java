package com.agentdrive.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
}
