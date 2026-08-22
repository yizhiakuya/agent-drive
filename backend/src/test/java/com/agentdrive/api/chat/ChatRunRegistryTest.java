package com.agentdrive.api.chat;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatRunRegistryTest {
    @Test
    void keepsRuntimeActiveAfterClientSubscriptionIsDisposedAndAllowsReconnect() {
        ChatRunRegistry registry = new ChatRunRegistry();
        Sinks.Many<ChatSseEvent> source = Sinks.many().replay().all();
        String sessionId = UUID.randomUUID().toString();
        ChatRequest request = new ChatRequest("继续", List.of(), List.of(), sessionId, "auto");
        ChatRuntime runtime = new ChatRuntime() {
            @Override
            public reactor.core.publisher.Mono<ChatResponse> complete(ChatRequest ignored) {
                return reactor.core.publisher.Mono.empty();
            }

            @Override
            public Flux<ChatSseEvent> stream(ChatRequest ignored) {
                return source.asFlux();
            }
        };

        List<ChatSseEvent> firstSubscriber = new CopyOnWriteArrayList<>();
        reactor.core.Disposable client = registry.start(request, runtime).subscribe(firstSubscriber::add);
        source.tryEmitNext(ChatSseEvents.text("处理中"));
        client.dispose();

        assertThat(registry.active(sessionId)).isTrue();
        List<ChatSseEvent> reconnect = new CopyOnWriteArrayList<>();
        registry.reconnect(sessionId).subscribe(reconnect::add);
        assertThat(firstSubscriber).extracting(ChatSseEvent::event).contains("text");
        assertThat(reconnect).extracting(ChatSseEvent::event).contains("text");

        registry.cancel(sessionId);
        assertThat(registry.active(sessionId)).isFalse();
        registry.close();
    }

    @Test
    void rejectsTwoConcurrentRunsForTheSameSession() {
        ChatRunRegistry registry = new ChatRunRegistry();
        String sessionId = UUID.randomUUID().toString();
        ChatRequest request = new ChatRequest("继续", List.of(), List.of(), sessionId, "auto");
        ChatRuntime runtime = new ChatRuntime() {
            @Override
            public reactor.core.publisher.Mono<ChatResponse> complete(ChatRequest ignored) {
                return reactor.core.publisher.Mono.empty();
            }

            @Override
            public Flux<ChatSseEvent> stream(ChatRequest ignored) {
                return Flux.never();
            }
        };

        registry.start(request, runtime).subscribe();
        assertThatThrownBy(() -> registry.start(request, runtime).subscribe())
                .isInstanceOf(ChatRunRegistry.ActiveChatRunException.class);
        registry.close();
    }
}
