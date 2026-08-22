package com.agentdrive.api.auth;

import com.agentdrive.auth.AuthAccountStore;
import com.agentdrive.auth.AuthenticatedPrincipal;
import com.agentdrive.auth.AuthService;
import com.agentdrive.auth.PasswordHasher;
import com.agentdrive.infrastructure.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerContractTest {
    @Test
    void setupLoginAndLogoutKeepExistingAuthShape() throws Exception {
        FakeAccountStore store = new FakeAccountStore();
        AuthController controller = new AuthController(
                new AuthService(store, new PasswordHasher()),
                new AppProperties("api", false),
                new WebRequestPrincipalResolver(credential -> Optional.of(
                        new AuthenticatedPrincipal(store.userId, AuthenticatedPrincipal.CredentialKind.SESSION)
                ))
        );
        WebTestClient client = WebTestClient.bindToController(controller)
                .controllerAdvice(new ChatAuthExceptionHandler())
                .build();
        ObjectMapper objectMapper = new ObjectMapper();

        String setupBody = client.post()
                .uri("/api/v1/auth/setup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("password", "password-123"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches("Set-Cookie", ".*agentdrive_session=.*Max-Age=2592000.*HttpOnly.*")
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        String setupToken = objectMapper.readTree(setupBody).get("session").asText();
        assertThat(setupToken).hasSize(43);

        String loginBody = client.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("password", "password-123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        String loginToken = objectMapper.readTree(loginBody).get("session").asText();
        assertThat(loginToken).hasSize(43).isNotEqualTo(setupToken);

        client.post()
                .uri("/api/v1/auth/logout")
                .cookie(AuthController.SESSION_COOKIE, loginToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.ok").isEqualTo(true);
    }

    @Test
    void deviceTokenAndPairingEndpointsPreserveMobileContract() throws Exception {
        FakeAccountStore store = new FakeAccountStore();
        AuthController controller = new AuthController(
                new AuthService(store, new PasswordHasher()),
                new AppProperties("api", false),
                new WebRequestPrincipalResolver(credential -> Optional.of(
                        new AuthenticatedPrincipal(store.userId, AuthenticatedPrincipal.CredentialKind.SESSION)
                ))
        );
        WebTestClient client = WebTestClient.bindToController(controller)
                .controllerAdvice(new ChatAuthExceptionHandler())
                .build();
        ObjectMapper objectMapper = new ObjectMapper();

        String deviceBody = client.post()
                .uri("/api/v1/auth/device-token")
                .header("Authorization", "Bearer session-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("device_id", "phone-1", "name", "Pixel"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(objectMapper.readTree(deviceBody).get("device_id").asText()).isEqualTo("phone-1");
        assertThat(objectMapper.readTree(deviceBody).get("token").asText()).hasSize(43);

        String pairingBody = client.post()
                .uri("/api/v1/auth/pairing")
                .cookie(AuthController.SESSION_COOKIE, "session-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        var pairing = objectMapper.readTree(pairingBody);
        String code = pairing.get("code").asText();
        assertThat(pairing.get("expires_in").asInt()).isEqualTo(300);

        String exchangedBody = client.post()
                .uri("/api/v1/auth/pair-exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", code, "device_id", "phone-2", "name", "Tablet"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(objectMapper.readTree(exchangedBody).get("device_id").asText()).isEqualTo("phone-2");
        assertThat(objectMapper.readTree(exchangedBody).get("token").asText()).hasSize(43);

        client.post()
                .uri("/api/v1/auth/pair-exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", code, "device_id", "phone-3"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(value ->
                        assertThat(String.valueOf(value)).contains("already been used"));
    }

    @Test
    void deviceTokenRejectsDeviceBearerEvenForTheSameOwner() {
        FakeAccountStore store = new FakeAccountStore();
        AuthController controller = new AuthController(
                new AuthService(store, new PasswordHasher()),
                new AppProperties("api", false),
                new WebRequestPrincipalResolver(credential -> Optional.of(
                        new AuthenticatedPrincipal(store.userId, AuthenticatedPrincipal.CredentialKind.DEVICE)
                ))
        );
        WebTestClient.bindToController(controller)
                .controllerAdvice(new ChatAuthExceptionHandler())
                .build()
                .post()
                .uri("/api/v1/auth/device-token")
                .header("Authorization", "Bearer existing-device-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("device_id", "phone-2"))
                .exchange()
                .expectStatus().isForbidden();
    }

    private static final class FakeAccountStore implements AuthAccountStore {
        private final UUID userId = UUID.randomUUID();
        private String passwordHash;
        private String pairingHash;
        private String consumedPairingHash;

        @Override
        public Optional<String> findOwnerPasswordHash() {
            return Optional.ofNullable(passwordHash);
        }

        @Override
        public Optional<UUID> findOwnerId() {
            return passwordHash == null ? Optional.empty() : Optional.of(userId);
        }

        @Override
        public Optional<UUID> createOwner(String passwordHash) {
            if (this.passwordHash != null) {
                return Optional.empty();
            }
            this.passwordHash = passwordHash;
            return Optional.of(userId);
        }

        @Override
        public void createSession(UUID ignoredUserId, String ignoredHash, Instant ignoredExpiry) {
        }

        @Override
        public boolean revokeSession(String ignoredHash) {
            return true;
        }

        @Override
        public boolean revokeDevice(String ignoredHash) {
            return false;
        }

        @Override
        public Optional<UUID> createPairing(UUID ignoredUserId, String ignoredCodeHash, Instant ignoredExpiry) {
            pairingHash = ignoredCodeHash;
            return Optional.of(UUID.randomUUID());
        }

        @Override
        public Optional<UUID> consumePairing(String ignoredCodeHash) {
            return Optional.empty();
        }

        @Override
        public boolean pairingWasConsumed(String ignoredCodeHash) {
            return ignoredCodeHash.equals(consumedPairingHash);
        }

        @Override
        public Optional<UUID> consumePairingAndReplaceDevice(String ignoredCodeHash,
                                                               String ignoredDeviceId,
                                                               String ignoredTokenHash,
                                                               String ignoredName) {
            if (!ignoredCodeHash.equals(pairingHash)) {
                return Optional.empty();
            }
            pairingHash = null;
            consumedPairingHash = ignoredCodeHash;
            return Optional.of(userId);
        }

        @Override
        public void replaceDeviceToken(UUID ignoredUserId, String ignoredDeviceId,
                                       String ignoredTokenHash, String ignoredName) {
        }
    }
}
