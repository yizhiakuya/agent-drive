package com.agentdrive.infrastructure.persistence;

import com.agentdrive.agent.ToolReplayStore;
import com.agentdrive.infrastructure.LlmApiKeyCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "AGENT_DRIVE_JDBC_TEST_URL", matches = ".+")
class MybatisChatRuntimeStateStoreIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MybatisChatRuntimeStateStore store;

    @Autowired
    private MybatisConversationSessionStore conversationSessions;

    @Autowired
    private MybatisLlmProviderConfigStore providerConfigs;

    @Test
    void roundTripsToolReplayAndConfirmationState() {
        String userId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        Map<String, Object> arguments = Map.of("operation", "POST /api/v1/config/test");
        Map<String, Object> parsed = Map.of("ok", true, "tested", true);
        Map<String, Object> pending = Map.of(
                "tool", "backend_api",
                "arguments", Map.of("operation", "INTERNAL delete_file"),
                "nonce", "integration-nonce",
                "ts", 1_700_000_000L,
                "signature", "integration-signature",
                "message", "redacted"
        );

        try {
            jdbc.update("INSERT INTO users(id, username, password_hash) VALUES (?::uuid, ?, ?)",
                    userId, "mybatis-integration-" + userId, "test-hash");
            jdbc.update("INSERT INTO chat_sessions(id, user_id) VALUES (?::uuid, ?::uuid)",
                    sessionId, userId);

            assertThat(conversationSessions.findOwned(UUID.fromString(userId), UUID.fromString(sessionId)))
                    .isPresent();
            assertThat(conversationSessions.findOwned(UUID.randomUUID(), UUID.fromString(sessionId)))
                    .isEmpty();
            assertThat(conversationSessions.create(UUID.fromString(userId)).userId())
                    .isEqualTo(UUID.fromString(userId));

            LlmApiKeyCipher cipher = new LlmApiKeyCipher(new byte[32]);
            providerConfigs.saveForOwner(
                    UUID.fromString(userId),
                    "openai_compat",
                    "http://provider.test/v1",
                    "model-a",
                    cipher.encrypt("sk-provider-secret"),
                    "provider-fingerprint"
            );
            assertThat(providerConfigs.findForOwner(UUID.fromString(userId)))
                    .get()
                    .satisfies(view -> {
                        assertThat(view.provider()).isEqualTo("openai_compat");
                        assertThat(view.model()).isEqualTo("model-a");
                        assertThat(view.apiKeyConfigured()).isTrue();
                        assertThat(view.apiKeyFingerprint()).isEqualTo("provider-fingerprint");
                    });
            assertThat(providerConfigs.encryptedApiKeyForOwner(UUID.fromString(userId)))
                    .get()
                    .satisfies(encrypted -> assertThat(cipher.decrypt(encrypted)).isEqualTo("sk-provider-secret"));

            store.save(sessionId, "backend_api", arguments, "{\"ok\":true}", parsed);

            ToolReplayStore.ToolReplay replay = store.find(sessionId, "backend_api", arguments);
            assertThat(replay).isNotNull();
            assertThat(replay.output()).isEqualTo("{\"ok\":true}");
            assertThat(replay.parsed()).containsEntry("tested", true);

            store.savePending(sessionId, pending);
            assertThat(store.findPending(sessionId, "backend_api", pendingArguments(pending)))
                    .containsEntry("nonce", "integration-nonce");
            assertThat(conversationSessions.findOwnedDetails(
                    UUID.fromString(userId), UUID.fromString(sessionId)))
                    .extracting(meta -> meta.get("pending_confirmation"))
                    .isInstanceOf(Map.class);
            assertThat(store.consumeNonce(sessionId, "integration-nonce")).isTrue();
            assertThat(store.consumeNonce(sessionId, "integration-nonce")).isFalse();
            store.clearPending(sessionId);
            assertThat(store.findPending(sessionId, "backend_api", pendingArguments(pending))).isNull();

            store.appendUser(sessionId, "hello sk-abcdefgh1234");
            store.appendAssistant(sessionId, "answer", "reasoning jina_abcdefgh1234");
            store.appendToolTrace(
                    sessionId,
                    "backend_api",
                    Map.of("api_key", "secret-value"),
                    "Bearer abcdefgh1234",
                    Map.of("token", "device-secret", "ok", true)
            );
            assertThat(store.appendContextIfChanged(
                    sessionId, "skill-catalog", "skill-catalog", "catalog sk-context-secret")).isTrue();
            assertThat(store.appendContextIfChanged(
                    sessionId, "skill-catalog", "skill-catalog", "catalog sk-context-secret")).isFalse();
            assertThat(store.appendContextIfChanged(
                    sessionId, "skill-catalog", "skill-catalog", "updated catalog")).isTrue();
            store.updateLastTrace(sessionId, List.of(
                    Map.of("token", "trace-secret", "message", "sk-abcdefgh1234")
            ));

            List<Map<String, Object>> messages = jdbc.queryForList(
                    "SELECT role, content, reasoning, context_source, context_kind, "
                            + "arguments::text AS arguments, parsed::text AS parsed "
                            + "FROM chat_messages WHERE session_id = ?::uuid ORDER BY id",
                    sessionId
            );
            assertThat(messages).hasSize(5);
            assertThat(messages.get(0).get("content")).isEqualTo("hello [REDACTED]");
            assertThat(messages.get(1).get("reasoning")).isEqualTo("reasoning [REDACTED]");
            assertThat(messages.get(2).get("content")).isEqualTo("Bearer [REDACTED]");
            assertThat(String.valueOf(messages.get(2).get("arguments")))
                    .contains("***")
                    .doesNotContain("secret-value");
            assertThat(String.valueOf(messages.get(2).get("parsed")))
                    .contains("***")
                    .doesNotContain("device-secret");
            assertThat(messages.get(3))
                    .containsEntry("role", "context")
                    .containsEntry("content", "catalog [REDACTED]")
                    .containsEntry("context_source", "skill-catalog")
                    .containsEntry("context_kind", "skill-catalog");
            assertThat(messages.get(4)).containsEntry("content", "updated catalog");

            assertThat(conversationSessions.listOwned(UUID.fromString(userId)))
                    .anySatisfy(meta -> assertThat(meta).containsEntry("id", sessionId));
            assertThat(conversationSessions.findOwnedDetails(UUID.fromString(userId), UUID.fromString(sessionId)))
                    .containsEntry("id", sessionId);
            assertThat(conversationSessions.messagesOwned(UUID.fromString(userId), UUID.fromString(sessionId)))
                    .hasSize(5)
                    .first()
                    .satisfies(message -> assertThat(((Map<?, ?>) message).get("role")).isEqualTo("user"));
            assertThat(conversationSessions.messagesOwned(
                    UUID.fromString(userId), UUID.fromString(sessionId)))
                    .anySatisfy(message -> assertThat(message)
                            .containsEntry("role", "context")
                            .containsEntry("context_source", "skill-catalog")
                            .containsEntry("context_kind", "skill-catalog"));

            String lastTrace = jdbc.queryForObject(
                    "SELECT last_trace::text FROM chat_sessions WHERE id = ?::uuid",
                    String.class,
                    sessionId
            );
            assertThat(lastTrace).contains("***").doesNotContain("trace-secret", "sk-abcdefgh1234");
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?::uuid", userId);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pendingArguments(Map<String, Object> pending) {
        return (Map<String, Object>) pending.get("arguments");
    }
}
