package com.agentdrive.api.sessions;

import com.agentdrive.api.auth.ChatAuthExceptionHandler;
import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.auth.AuthenticatedPrincipal;
import com.agentdrive.auth.ConversationSession;
import com.agentdrive.auth.ConversationSessionService;
import com.agentdrive.auth.ConversationSessionStore;
import com.agentdrive.auth.CredentialAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class SessionControllerContractTest {
    @Test
    void sessionDetailsAndDeleteStayOwnerScoped() {
        UUID owner = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        StubSessions store = new StubSessions(owner, sessionId);
        WebTestClient client = client(owner, store);

        client.get().uri("/api/v1/sessions").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.sessions[0].id").isEqualTo(sessionId.toString());

        client.get().uri("/api/v1/sessions/" + sessionId).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.meta.title").isEqualTo("A session")
                .jsonPath("$.messages[0].role").isEqualTo("user");

        client.delete().uri("/api/v1/sessions/" + sessionId).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.deleted").isEqualTo(sessionId.toString());
    }

    @Test
    void summarizesOwnedSessionAndPersistsDerivedTitle() {
        UUID owner = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        StubSessions store = new StubSessions(owner, sessionId);
        WebTestClient client = client(owner, store);

        client.post().uri("/api/v1/sessions/" + sessionId + "/summarize").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.ok").isEqualTo(true)
                .jsonPath("$.summary").isEqualTo("hello")
                .jsonPath("$.title").isEqualTo("hello");

        if (!"hello".equals(store.summary) || !"hello".equals(store.title)) {
            throw new AssertionError("summary was not persisted");
        }
    }

    @Test
    void foreignSessionReturnsNotFound() {
        UUID owner = UUID.randomUUID();
        StubSessions store = new StubSessions(owner, UUID.randomUUID());
        WebTestClient client = client(owner, store);

        client.get().uri("/api/v1/sessions/" + UUID.randomUUID()).exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.detail").isEqualTo("chat session does not exist");
    }

    private WebTestClient client(UUID owner, StubSessions store) {
        CredentialAuthenticator authenticator = credential ->
                "session-token".equals(credential)
                        ? Optional.of(new AuthenticatedPrincipal(owner, AuthenticatedPrincipal.CredentialKind.SESSION))
                        : Optional.empty();
        return WebTestClient.bindToController(new SessionController(
                        new ConversationSessionService(store), new WebRequestPrincipalResolver(authenticator)))
                .controllerAdvice(new ChatAuthExceptionHandler())
                .build()
                .mutate().defaultCookie("agentdrive_session", "session-token").build();
    }

    private static final class StubSessions implements ConversationSessionStore {
        private final UUID owner;
        private final UUID sessionId;
        private String summary;
        private String title;

        private StubSessions(UUID owner, UUID sessionId) {
            this.owner = owner;
            this.sessionId = sessionId;
        }

        @Override
        public Optional<ConversationSession> findOwned(UUID userId, UUID requested) {
            return userId.equals(owner) && sessionId.equals(requested)
                    ? Optional.of(new ConversationSession(sessionId, owner))
                    : Optional.empty();
        }

        @Override
        public ConversationSession create(UUID userId) {
            return new ConversationSession(sessionId, userId);
        }

        @Override
        public List<Map<String, Object>> listOwned(UUID userId) {
            return userId.equals(owner)
                    ? List.of(Map.of("id", sessionId.toString(), "title", "A session"))
                    : List.of();
        }

        @Override
        public Map<String, Object> findOwnedDetails(UUID userId, UUID requested) {
            return userId.equals(owner) && sessionId.equals(requested)
                    ? Map.of("id", sessionId.toString(), "title", "A session")
                    : null;
        }

        @Override
        public List<Map<String, Object>> messagesOwned(UUID userId, UUID requested) {
            return List.of(Map.of("role", "user", "content", "hello", "ts", 1.0));
        }

        @Override
        public boolean deleteOwned(UUID userId, UUID requested) {
            return userId.equals(owner) && sessionId.equals(requested);
        }

        @Override
        public boolean updateSummary(UUID userId, UUID requested, String summary, String title) {
            if (!userId.equals(owner) || !sessionId.equals(requested)) return false;
            this.summary = summary;
            this.title = title;
            return true;
        }
    }
}
