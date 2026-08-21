package com.agentdrive.api.config;

import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.auth.AuthenticatedPrincipal;
import com.agentdrive.auth.CredentialAuthenticator;
import com.agentdrive.vision.VisionConfigStore;
import com.agentdrive.vision.VisionConfigurationService;
import com.agentdrive.vision.VisionModelClient;
import com.agentdrive.vision.VisionSecretCipher;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VisionConfigControllerContractTest {
    @Test
    void revealsSavedVisionKeyOnlyForSessionWithoutCaching() {
        UUID owner = UUID.randomUUID();
        WebTestClient client = client(owner, "vision-secret");

        client.post()
                .uri("/api/v1/config/vision/api-key/reveal")
                .cookie("agentdrive_session", "session-token")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().valueEquals(HttpHeaders.PRAGMA, "no-cache")
                .expectBody()
                .jsonPath("$.api_key").isEqualTo("vision-secret");
    }

    @Test
    void rejectsDeviceTokenForVisionKeyReveal() {
        UUID owner = UUID.randomUUID();
        WebTestClient client = client(owner, "vision-secret");

        client.post()
                .uri("/api/v1/config/vision/api-key/reveal")
                .header(HttpHeaders.AUTHORIZATION, "Bearer device-token")
                .exchange()
                .expectStatus().isForbidden();
    }

    private WebTestClient client(UUID owner, String apiKey) {
        byte[] encrypted = {1, 2, 3};
        VisionConfigStore store = mock(VisionConfigStore.class);
        VisionSecretCipher cipher = mock(VisionSecretCipher.class);
        when(store.find(owner)).thenReturn(Optional.of(new VisionConfigStore.VisionConfig(
                "openai_compat", "https://vision.example/v1", "vision-model", encrypted)));
        when(cipher.decrypt(encrypted)).thenReturn(apiKey);
        VisionConfigurationService configs = new VisionConfigurationService(
                store, cipher, mock(VisionModelClient.class));
        CredentialAuthenticator authenticator = credential -> switch (credential) {
            case "session-token" -> Optional.of(new AuthenticatedPrincipal(
                    owner, AuthenticatedPrincipal.CredentialKind.SESSION));
            case "device-token" -> Optional.of(new AuthenticatedPrincipal(
                    owner, AuthenticatedPrincipal.CredentialKind.DEVICE));
            default -> Optional.empty();
        };
        return WebTestClient.bindToController(new VisionConfigController(
                        configs, new WebRequestPrincipalResolver(authenticator)))
                .build();
    }
}
