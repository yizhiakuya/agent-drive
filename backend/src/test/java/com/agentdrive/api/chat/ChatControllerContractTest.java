package com.agentdrive.api.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ChatControllerContractTest {
    @Test
    void streamKeepsHeadersAndSerializesEveryEventAsJsonObject() {
        AtomicReference<ChatRequest> seenRequest = new AtomicReference<>();
        ChatRuntime runtime = new ChatRuntime() {
            @Override
            public Mono<ChatResponse> complete(ChatRequest request) {
                return Mono.error(new UnsupportedOperationException());
            }

            @Override
            public Flux<ChatSseEvent> stream(ChatRequest request) {
                seenRequest.set(request);
                return Flux.just(
                        ChatSseEvents.reasoning("先检查范围"),
                        ChatSseEvents.text("已完成"),
                        ChatSseEvents.toolStart(1, "backend_api", Map.of("action", "discover")),
                        ChatSseEvents.toolTrace(1, "backend_api", Map.of("action", "discover"),
                                "{\"ok\":true}", Map.of("ok", true), false, false),
                        ChatSseEvents.done(Map.of("steps", 1, "routed", "task"))
                );
            }
        };

        EntityExchangeResult<String> result = WebTestClient.bindToController(new ChatController(runtime, new ObjectMapper()))
                .build()
                .post()
                .uri("/api/v1/chat/stream")
                .header("X-Request-ID", "chat-test-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("message", "检查文件"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectHeader().valueEquals("Cache-Control", "no-cache")
                .expectHeader().valueEquals("X-Accel-Buffering", "no")
                .expectHeader().valueEquals("X-Request-ID", "chat-test-1")
                .expectBody(String.class)
                .returnResult();

        String body = result.getResponseBody();
        assertThat(body).contains("event: reasoning\ndata: {\"text\":\"先检查范围\"}");
        assertThat(body).contains("event: tool_start");
        assertThat(body).contains("\"step\":1");
        assertThat(body).contains("event: done");
        assertThat(body.indexOf("event: reasoning")).isLessThan(body.indexOf("event: text"));
        assertThat(body.indexOf("event: text")).isLessThan(body.indexOf("event: tool_start"));
        assertThat(body.indexOf("event: tool_start")).isLessThan(body.indexOf("event: tool_trace"));
        assertThat(body.indexOf("event: tool_trace")).isLessThan(body.indexOf("event: done"));
        assertThat(seenRequest.get().requestId()).isEqualTo("chat-test-1");
    }

    @Test
    void streamErrorsBecomeJsonErrorEventAfterHttp200() {
        ChatRuntime runtime = new ChatRuntime() {
            @Override
            public Mono<ChatResponse> complete(ChatRequest request) {
                return Mono.error(new UnsupportedOperationException());
            }

            @Override
            public Flux<ChatSseEvent> stream(ChatRequest request) {
                return Flux.concat(
                        Flux.just(ChatSseEvents.text("已发送")),
                        Flux.error(new IllegalStateException("provider failed?api_key=sk-secret-value&token=secret-token"))
                );
            }
        };

        String body = WebTestClient.bindToController(new ChatController(runtime, new ObjectMapper()))
                .build()
                .post()
                .uri("/api/v1/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("message", "继续"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("event: text");
        assertThat(body).contains("event: error");
        assertThat(body).contains("provider failed");
        assertThat(body).doesNotContain("sk-secret-value", "secret-token");
    }
}
