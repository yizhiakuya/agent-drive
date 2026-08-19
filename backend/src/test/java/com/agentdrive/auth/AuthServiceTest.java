package com.agentdrive.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthServiceTest {
    @Test
    void setupAndLoginCreateOpaquePersistedSessions() {
        FakeAccountStore store = new FakeAccountStore();
        AuthService service = new AuthService(store, new PasswordHasher());

        AuthService.LoginResult first = service.setup("password-123");
        AuthService.LoginResult second = service.login("password-123");

        assertThat(service.initialized()).isTrue();
        assertThat(first.sessionToken()).hasSize(43).isNotEqualTo(second.sessionToken());
        assertThat(store.sessionHash).isEqualTo(CredentialHash.sha256(second.sessionToken()));
        assertThat(store.expiresAt).isAfter(Instant.now());
    }

    @Test
    void rejectsInvalidPasswordAndRepeatedSetup() {
        FakeAccountStore store = new FakeAccountStore();
        AuthService service = new AuthService(store, new PasswordHasher());

        assertThatThrownBy(() -> service.setup("short"))
                .isInstanceOf(AuthService.InvalidPasswordException.class);
        service.setup("password-123");
        assertThatThrownBy(() -> service.setup("password-456"))
                .isInstanceOf(AuthService.PasswordAlreadySetException.class);
        assertThatThrownBy(() -> service.login("wrong-password"))
                .isInstanceOf(AuthService.AuthenticationFailedException.class);
    }

    @Test
    void logoutRevokesCookieAndBearerCredentials() {
        FakeAccountStore store = new FakeAccountStore();
        AuthService service = new AuthService(store, new PasswordHasher());
        service.setup("password-123");

        assertThat(service.logout("cookie-token", "device-token")).isTrue();
        assertThat(store.revokedSessionHash).isEqualTo(CredentialHash.sha256("device-token"));
        assertThat(store.revokedDeviceHash).isEqualTo(CredentialHash.sha256("device-token"));
    }

    @Test
    void issuesDeviceTokenAndConsumesPairingOnce() {
        FakeAccountStore store = new FakeAccountStore();
        AuthService service = new AuthService(store, new PasswordHasher());
        service.setup("password-123");

        AuthService.DeviceTokenResult device = service.issueDeviceToken(
                store.userId, "phone-1", "Phone"
        );
        AuthService.PairingResult pairing = service.issuePairing(store.userId);
        AuthService.DeviceTokenResult exchanged = service.exchangePairing(
                pairing.code(), "phone-2", "Tablet"
        );

        assertThat(device.token()).hasSize(43);
        assertThat(store.deviceHash).isEqualTo(CredentialHash.sha256(exchanged.token()));
        assertThat(pairing.expiresIn()).isEqualTo(300);
        assertThatThrownBy(() -> service.exchangePairing(pairing.code(), "phone-3", "Other"))
                .isInstanceOf(AuthService.InvalidPairingException.class);
    }

    private static final class FakeAccountStore implements AuthAccountStore {
        private final UUID userId = UUID.randomUUID();
        private String passwordHash;
        private String sessionHash;
        private Instant expiresAt;
        private String revokedSessionHash;
        private String revokedDeviceHash;
        private String pairingHash;
        private String consumedPairingHash;
        private String deviceHash;

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
        public void createSession(UUID userId, String credentialHash, Instant expiresAt) {
            this.sessionHash = credentialHash;
            this.expiresAt = expiresAt;
        }

        @Override
        public boolean revokeSession(String credentialHash) {
            revokedSessionHash = credentialHash;
            return true;
        }

        @Override
        public boolean revokeDevice(String credentialHash) {
            revokedDeviceHash = credentialHash;
            return true;
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
            deviceHash = ignoredTokenHash;
            return Optional.of(userId);
        }

        @Override
        public void replaceDeviceToken(UUID ignoredUserId, String ignoredDeviceId,
                                       String ignoredTokenHash, String ignoredName) {
        }
    }
}
