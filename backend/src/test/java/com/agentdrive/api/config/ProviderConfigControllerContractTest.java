package com.agentdrive.api.config;

import com.agentdrive.auth.AuthenticatedPrincipal;
import com.agentdrive.auth.CredentialAuthenticator;
import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.infrastructure.LlmApiKeyCipher;
import com.agentdrive.infrastructure.EmbeddingConfigStore;
import com.agentdrive.infrastructure.LlmProviderConfigService;
import com.agentdrive.infrastructure.LlmProviderConfigView;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderConfigControllerContractTest {
    @Test
    void returnsOwnerScopedMaskedConfigWithoutPlaintextKey() {
        UUID owner = UUID.randomUUID();
        LlmApiKeyCipher cipher = new LlmApiKeyCipher(new byte[32]);
        FakeConfigService configs = new FakeConfigService(owner, cipher.encrypt("sk-provider-secret"));
        WebTestClient client = client(owner, configs, cipher);

        String body = client.get()
                .uri("/api/v1/config")
                .header("Authorization", "Bearer session-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("\"configured\":true", "\"api_key_masked\":\"sk-pro…\"");
        assertThat(body).doesNotContain("sk-provider-secret");
    }

    @Test
    void revealsSavedLlmKeyOnlyForSessionWithoutCaching() {
        UUID owner = UUID.randomUUID();
        LlmApiKeyCipher cipher = new LlmApiKeyCipher(new byte[32]);
        WebTestClient client = client(owner,
                new FakeConfigService(owner, cipher.encrypt("sk-provider-secret")), cipher);

        client.post()
                .uri("/api/v1/config/api-key/reveal")
                .cookie("agentdrive_session", "session-token")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().valueEquals(HttpHeaders.PRAGMA, "no-cache")
                .expectBody()
                .jsonPath("$.api_key").isEqualTo("sk-provider-secret");
    }

    @Test
    void rejectsDeviceTokenForLlmKeyReveal() {
        UUID owner = UUID.randomUUID();
        LlmApiKeyCipher cipher = new LlmApiKeyCipher(new byte[32]);
        WebTestClient client = client(owner,
                new FakeConfigService(owner, cipher.encrypt("sk-provider-secret")), cipher);

        client.post()
                .uri("/api/v1/config/api-key/reveal")
                .header(HttpHeaders.AUTHORIZATION, "Bearer device-token")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @Test
    void returnsNotFoundWhenLlmKeyHasNotBeenSaved() {
        UUID owner = UUID.randomUUID();
        LlmApiKeyCipher cipher = new LlmApiKeyCipher(new byte[32]);
        WebTestClient client = client(owner, new FakeConfigService(owner, null), cipher);

        client.post()
                .uri("/api/v1/config/api-key/reveal")
                .cookie("agentdrive_session", "session-token")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @Test
    void rejectsInvalidProviderConfigurationWithBadRequest() {
        UUID owner = UUID.randomUUID();
        LlmApiKeyCipher cipher = new LlmApiKeyCipher(new byte[32]);
        WebTestClient client = client(owner, new FakeConfigService(owner, null), cipher);

        client.post()
                .uri("/api/v1/config/test")
                .header("Authorization", "Bearer session-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "type", "not-a-provider",
                        "base_url", "file:///tmp/provider",
                        "api_key", "test-key"
                ))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("未知 Provider 类型: not-a-provider");
    }

    @Test
    void exposesOwnerScopedEmbeddingStatusWithoutPlaintextKey() throws Exception {
        UUID owner = UUID.randomUUID();
        LlmApiKeyCipher cipher = new LlmApiKeyCipher(new byte[32]);
        EmbeddingConfigStore embeddings = mock(EmbeddingConfigStore.class);
        when(embeddings.find(owner)).thenReturn(Optional.of(new EmbeddingConfigStore.EmbeddingConfig(
                "jina", "https://api.jina.ai/v1", "jina-embeddings-v3", cipher.encrypt("jina-secret"))));
        WebTestClient client = embeddingClient(owner, new FakeConfigService(owner, null), cipher, embeddings);

        String body = client.get().uri("/api/v1/config/status")
                .header("Authorization", "Bearer session-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("\"configured\":true", "\"provider\":\"jina\"", "\"api_key_masked\":\"jina-s…\"");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
                .at("/embeddings/configured").asBoolean()).isTrue();
        assertThat(body).doesNotContain("jina-secret");
    }

    @Test
    void revealsSavedEmbeddingKeyOnlyOnDedicatedUncachedEndpoint() {
        UUID owner = UUID.randomUUID();
        LlmApiKeyCipher cipher = new LlmApiKeyCipher(new byte[32]);
        EmbeddingConfigStore embeddings = mock(EmbeddingConfigStore.class);
        when(embeddings.find(owner)).thenReturn(Optional.of(new EmbeddingConfigStore.EmbeddingConfig(
                "jina", "https://api.jina.ai/v1", "jina-embeddings-v3", cipher.encrypt("jina-secret"))));
        WebTestClient client = embeddingClient(owner, new FakeConfigService(owner, null), cipher, embeddings);

        client.post()
                .uri("/api/v1/config/embeddings/api-key/reveal")
                .cookie("agentdrive_session", "session-token")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().valueEquals(HttpHeaders.PRAGMA, "no-cache")
                .expectBody()
                .jsonPath("$.api_key").isEqualTo("jina-secret");
    }

    @Test
    void rejectsUnsupportedEmbeddingProviderWithBadRequest() {
        UUID owner = UUID.randomUUID();
        LlmApiKeyCipher cipher = new LlmApiKeyCipher(new byte[32]);
        WebTestClient client = embeddingClient(owner, new FakeConfigService(owner, null), cipher, mock(EmbeddingConfigStore.class));

        client.put().uri("/api/v1/config/embeddings")
                .header("Authorization", "Bearer session-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("provider", "unsupported", "api_key", "test-key"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.code").isEqualTo("bad_request")
                .jsonPath("$.ok").isEqualTo(false)
                .jsonPath("$.detail").isEqualTo("当前仅支持 Jina embedding provider");
    }

    @Test
    void failedEmbeddingProbeDoesNotOverwriteExistingConfigurationOrClaimSuccess() {
        UUID owner = UUID.randomUUID();
        LlmApiKeyCipher cipher = new LlmApiKeyCipher(new byte[32]);
        EmbeddingConfigStore embeddings = mock(EmbeddingConfigStore.class);
        EmbeddingProbeClient probe = mock(EmbeddingProbeClient.class);
        when(probe.test("http://127.0.0.1:19091/v1", "jina-embeddings-v3", "new-secret"))
                .thenReturn(Map.of("ok", false, "status", 401, "error", "embedding provider returned HTTP 401"));
        ProviderConfigController controller = new ProviderConfigController(
                new FakeConfigService(owner, null), cipher, new WebRequestPrincipalResolver(authenticator(owner)),
                new com.fasterxml.jackson.databind.ObjectMapper(), embeddings, probe);
        WebTestClient client = WebTestClient.bindToController(controller)
                .controllerAdvice(new ProviderConfigExceptionHandler()).build();

        client.put().uri("/api/v1/config/embeddings")
                .header("Authorization", "Bearer session-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("provider", "jina", "base_url", "http://127.0.0.1:19091/v1",
                        "model", "jina-embeddings-v3", "api_key", "new-secret"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.ok").isEqualTo(false)
                .jsonPath("$.saved").doesNotExist()
                .jsonPath("$.message").isEqualTo("连接测试失败，配置未保存");
        verify(embeddings, never()).save(org.mockito.ArgumentMatchers.eq(owner), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void requiresAuthenticationForConfigRead() {
        UUID owner = UUID.randomUUID();
        LlmApiKeyCipher cipher = new LlmApiKeyCipher(new byte[32]);
        WebTestClient client = client(owner, new FakeConfigService(owner, null), cipher);

        client.get()
                .uri("/api/v1/config")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private static WebTestClient client(UUID owner, LlmProviderConfigService configs, LlmApiKeyCipher cipher) {
        CredentialAuthenticator authenticator = authenticator(owner);
        ProviderConfigController controller = new ProviderConfigController(
                configs,
                cipher,
                new WebRequestPrincipalResolver(authenticator),
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        return WebTestClient.bindToController(controller)
                .controllerAdvice(new ProviderConfigExceptionHandler())
                .build();
    }

    private static WebTestClient embeddingClient(UUID owner, LlmProviderConfigService configs,
                                                  LlmApiKeyCipher cipher, EmbeddingConfigStore embeddings) {
        CredentialAuthenticator authenticator = authenticator(owner);
        ProviderConfigController controller = new ProviderConfigController(
                configs,
                cipher,
                new WebRequestPrincipalResolver(authenticator),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                embeddings
        );
        return WebTestClient.bindToController(controller)
                .controllerAdvice(new ProviderConfigExceptionHandler())
                .build();
    }

    private static CredentialAuthenticator authenticator(UUID owner) {
        return credential -> switch (credential) {
            case "session-token" -> Optional.of(new AuthenticatedPrincipal(
                    owner, AuthenticatedPrincipal.CredentialKind.SESSION));
            case "device-token" -> Optional.of(new AuthenticatedPrincipal(
                    owner, AuthenticatedPrincipal.CredentialKind.DEVICE));
            default -> Optional.empty();
        };
    }

    private static final class FakeConfigService implements LlmProviderConfigService {
        private final UUID owner;
        private final byte[] encryptedKey;

        private FakeConfigService(UUID owner, byte[] encryptedKey) {
            this.owner = owner;
            this.encryptedKey = encryptedKey;
        }

        @Override
        public Optional<LlmProviderConfigView> findForOwner(UUID userId) {
            return owner.equals(userId)
                    ? Optional.of(new LlmProviderConfigView("openai_compat", "https://provider.test/v1", "model-a", encryptedKey != null, "fingerprint"))
                    : Optional.empty();
        }

        @Override
        public Optional<byte[]> encryptedApiKeyForOwner(UUID userId) {
            return owner.equals(userId) ? Optional.ofNullable(encryptedKey) : Optional.empty();
        }

        @Override
        public void saveForOwner(UUID userId, String provider, String baseUrl, String model,
                                 byte[] encryptedApiKey, String apiKeyFingerprint) {
        }
    }
}
