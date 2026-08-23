package com.agentdrive.api.chat;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatRunRegistryTest {
    @Test
    void reportsPersistedInterruptedStateAfterProcessRestart() {
        RecordingStateStore stateStore = new RecordingStateStore();
        ChatRunRegistry registry = new ChatRunRegistry(2, Duration.ofSeconds(30), stateStore);
        stateStore.state.put("status", "interrupted");
        stateStore.state.put("phase", "process_restart");
        stateStore.state.put("resumable", true);

        registry.markStaleRunsInterrupted();

        assertThat(stateStore.interruptedCalls).isEqualTo(1);
        assertThat(registry.state(UUID.randomUUID().toString()))
                .containsEntry("status", "interrupted")
                .containsEntry("phase", "process_restart")
                .containsEntry("resumable", true)
                .containsEntry("active", false);
        registry.close();
    }

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
        awaitSubscriber(source);
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

    private static void awaitSubscriber(Sinks.Many<ChatSseEvent> source) {
        for (int attempt = 0; attempt < 100 && source.currentSubscriberCount() == 0; attempt++) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("test interrupted", interrupted);
            }
        }
        assertThat(source.currentSubscriberCount()).isGreaterThan(0);
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

    @Test
    void enforcesProcessActiveRunCapacity() {
        ChatRunRegistry registry = new ChatRunRegistry(1, Duration.ofSeconds(30));
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
        ChatRequest first = new ChatRequest("一", List.of(), List.of(), UUID.randomUUID().toString(), "auto");
        ChatRequest second = new ChatRequest("二", List.of(), List.of(), UUID.randomUUID().toString(), "auto");

        registry.start(first, runtime).subscribe();
        assertThatThrownBy(() -> registry.start(second, runtime).subscribe())
                .isInstanceOf(ChatRunRegistry.ActiveChatRunException.class);
        registry.close();
    }

    private static final class RecordingStateStore implements com.agentdrive.agent.ChatRunStateStore {
        private final Map<String, Object> state = new LinkedHashMap<>();
        private int interruptedCalls;

        @Override
        public void start(String sessionId) {
            state.put("status", "running");
            state.put("phase", "starting");
        }

        @Override
        public void update(String sessionId, String status, String phase) {
            state.put("status", status);
            state.put("phase", phase);
        }

        @Override
        public Map<String, Object> find(String sessionId) {
            return new LinkedHashMap<>(state);
        }

        @Override
        public void markInterrupted() {
            interruptedCalls++;
        }
    }
}
