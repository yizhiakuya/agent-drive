package com.agentdrive.api.chat;

import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.auth.AuthenticatedPrincipal;
import com.agentdrive.auth.ConversationSession;
import com.agentdrive.auth.ConversationSessionService;
import com.agentdrive.auth.ConversationSessionStore;
import com.agentdrive.tasks.TaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatRunControllerContractTest {
    @Test
    void enqueuesOwnerScopedChatRunWithoutPersistingInlineImages() {
        UUID owner = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        TaskStore tasks = mock(TaskStore.class);
        TaskStore.EnqueueResult queued = new TaskStore.EnqueueResult(
                Map.of("id", "task-1", "type", "chat.run", "status", "queued"), true);
        when(tasks.enqueue(any(UUID.class), eq("chat.run"), eq("automation"), anyMap(), anyString(),
                eq("api"), isNull(UUID.class), eq(0), eq(1))).thenReturn(queued);

        ConversationSessionStore sessions = new ConversationSessionStore() {
            @Override
            public Optional<ConversationSession> findOwned(UUID ignoredUserId, UUID ignoredSessionId) {
                return Optional.empty();
            }

            @Override
            public ConversationSession create(UUID actualUserId) {
                return new ConversationSession(sessionId, actualUserId);
            }

            @Override
            public Map<String, Object> findOwnedDetails(UUID ignoredUserId, UUID ignoredSessionId) {
                return Map.of("id", sessionId.toString());
            }

            @Override
            public java.util.List<Map<String, Object>> messagesOwned(UUID ignoredUserId, UUID ignoredSessionId) {
                return java.util.List.of();
            }
        };
        WebRequestPrincipalResolver resolver = new WebRequestPrincipalResolver(credential ->
                "session-token".equals(credential)
                        ? Optional.of(new AuthenticatedPrincipal(owner, AuthenticatedPrincipal.CredentialKind.SESSION))
                        : Optional.empty());
        ChatRuntime runtime = new ChatRuntime() {
            @Override
            public Mono<ChatResponse> complete(ChatRequest ignored) {
                return Mono.empty();
            }

            @Override
            public Flux<ChatSseEvent> stream(ChatRequest ignored) {
                return Flux.empty();
            }
        };
        ChatController controller = new ChatController(runtime, new ObjectMapper(),
                new ConversationSessionService(sessions), resolver, tasks, new ChatRunRegistry());

        WebTestClient.bindToController(controller)
                .build()
                .post()
                .uri("/api/v1/chat/run")
                .header("Authorization", "Bearer session-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "message", "整理文件",
                        "inline_images", java.util.List.of(Map.of(
                                "name", "clipboard.png", "media_type", "image/png", "data", "aGVsbG8="))))
                .exchange()
                .expectStatus().is4xxClientError();

        WebTestClient.bindToController(controller)
                .build()
                .post()
                .uri("/api/v1/chat/run")
                .header("Authorization", "Bearer session-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("message", "整理文件", "file_context", java.util.List.of("notes/today.md")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.queued").isEqualTo(true)
                .jsonPath("$.task.id").isEqualTo("task-1");

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> payload =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(tasks).enqueue(eq(owner), eq("chat.run"), eq("automation"), payload.capture(), anyString(),
                eq("api"), isNull(UUID.class), eq(0), eq(1));
        assertThat(payload.getValue()).containsEntry("session_id", sessionId.toString())
                .containsEntry("message", "整理文件")
                .containsEntry("file_context", java.util.List.of("notes/today.md"))
                .doesNotContainKey("inline_images");
    }
}
