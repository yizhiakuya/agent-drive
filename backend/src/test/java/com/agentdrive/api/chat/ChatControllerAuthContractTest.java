package com.agentdrive.api.chat;

import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.auth.AuthenticatedPrincipal;
import com.agentdrive.auth.ConversationSession;
import com.agentdrive.auth.ConversationSessionService;
import com.agentdrive.auth.ConversationSessionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ChatControllerAuthContractTest {
    @Test
    void authenticatesAndCreatesOwnedSessionBeforeRuntime() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        AtomicReference<ChatRequest> seen = new AtomicReference<>();
        ChatRuntime runtime = new ChatRuntime() {
            @Override
            public Mono<ChatResponse> complete(ChatRequest request) {
                seen.set(request);
                return Mono.just(new ChatResponse(
                        "ok", List.of(), 0, 0, null, null, false,
                        "chat", List.of(), Map.of(), Map.of(), false
                ));
            }

            @Override
            public Flux<ChatSseEvent> stream(ChatRequest request) {
                return Flux.empty();
            }
        };
        ConversationSessionStore sessions = new ConversationSessionStore() {
            @Override
            public Optional<ConversationSession> findOwned(UUID ignoredUserId, UUID ignoredSessionId) {
                return Optional.empty();
            }

            @Override
            public ConversationSession create(UUID actualUserId) {
                return new ConversationSession(sessionId, actualUserId);
            }
        };
        WebRequestPrincipalResolver resolver = new WebRequestPrincipalResolver(credential ->
                "session-token".equals(credential)
                        ? Optional.of(new AuthenticatedPrincipal(
                                userId, AuthenticatedPrincipal.CredentialKind.SESSION
                        ))
                        : Optional.empty());
        ChatController controller = new ChatController(
                runtime,
                new ObjectMapper(),
                new ConversationSessionService(sessions),
                resolver
        );

        WebTestClient.bindToController(controller)
                .build()
                .post()
                .uri("/api/v1/chat")
                .header("Authorization", "Bearer session-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("message", "hello"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reply").isEqualTo("ok");

        assertThat(seen.get().sessionId()).isEqualTo(sessionId.toString());
        assertThat(seen.get().authenticatedUserId()).isEqualTo(userId);
    }

    @Test
    void streamErrorKeepsSessionCreatedBeforeProviderFailure() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ChatRuntime runtime = new ChatRuntime() {
            @Override
            public Mono<ChatResponse> complete(ChatRequest request) {
                return Mono.error(new UnsupportedOperationException());
            }

            @Override
            public Flux<ChatSseEvent> stream(ChatRequest request) {
                return Flux.error(new IllegalStateException("upstream rate limit"));
            }
        };
        ConversationSessionStore sessions = new ConversationSessionStore() {
            @Override
            public Optional<ConversationSession> findOwned(UUID ignoredUserId, UUID ignoredSessionId) {
                return Optional.empty();
            }

            @Override
            public ConversationSession create(UUID actualUserId) {
                return new ConversationSession(sessionId, actualUserId);
            }
        };
        WebRequestPrincipalResolver resolver = new WebRequestPrincipalResolver(credential ->
                "session-token".equals(credential)
                        ? Optional.of(new AuthenticatedPrincipal(
                                userId, AuthenticatedPrincipal.CredentialKind.SESSION
                        ))
                        : Optional.empty());

        String body = WebTestClient.bindToController(new ChatController(
                        runtime,
                        new ObjectMapper(),
                        new ConversationSessionService(sessions),
                        resolver
                ))
                .build()
                .post()
                .uri("/api/v1/chat/stream")
                .header("Authorization", "Bearer session-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("message", "hello"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("event: error");
        assertThat(body).contains("upstream rate limit");
        assertThat(body).contains("\"session_id\":\"" + sessionId + "\"");
    }
}
