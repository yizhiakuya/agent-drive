package com.agentdrive.infrastructure.persistence;

import com.agentdrive.auth.AuthAccountStore;
import com.agentdrive.auth.AuthenticatedPrincipal;
import com.agentdrive.auth.CredentialAuthenticator;
import com.agentdrive.auth.CredentialHash;
import com.agentdrive.auth.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "AGENT_DRIVE_JDBC_TEST_URL", matches = ".+")
class MybatisAuthAccountStoreIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuthAccountStore accounts;

    @Autowired
    private CredentialAuthenticator credentials;

    @Autowired
    private PasswordHasher passwords;

    @Test
    void createsOwnerSessionAndRevokesCredential() {
        assumeTrue(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE username = 'owner'", Integer.class
        ) == 0, "dedicated integration database already has an owner");
        String passwordHash = passwords.hash("integration-password");
        String rawToken = "integration-session-token-" + UUID.randomUUID();

        try {
            UUID userId = accounts.createOwner(passwordHash).orElseThrow();
            assertThat(accounts.findOwnerId()).contains(userId);
            assertThat(accounts.findOwnerPasswordHash()).contains(passwordHash);

            accounts.createSession(userId, com.agentdrive.auth.CredentialHash.sha256(rawToken),
                    Instant.now().plusSeconds(300));
            AuthenticatedPrincipal principal = credentials.authenticate(rawToken).orElseThrow();
            assertThat(principal.userId()).isEqualTo(userId);
            assertThat(principal.credentialKind())
                    .isEqualTo(AuthenticatedPrincipal.CredentialKind.SESSION);

            assertThat(accounts.revokeSession(CredentialHash.sha256(rawToken)))
                    .isTrue();
            assertThat(credentials.authenticate(rawToken)).isEmpty();

            String pairingCode = "integration-pairing-" + UUID.randomUUID();
            assertThat(accounts.createPairing(userId, CredentialHash.sha256(pairingCode),
                    Instant.now().plusSeconds(300))).isPresent();
            String rawDeviceToken = "integration-device-token-" + UUID.randomUUID();
            assertThat(accounts.consumePairingAndReplaceDevice(
                    CredentialHash.sha256(pairingCode), "device-1",
                    CredentialHash.sha256(rawDeviceToken), "Integration phone"
            )).contains(userId);
            AuthenticatedPrincipal device = credentials.authenticate(rawDeviceToken).orElseThrow();
            assertThat(device.userId()).isEqualTo(userId);
            assertThat(device.credentialKind())
                    .isEqualTo(AuthenticatedPrincipal.CredentialKind.DEVICE);
            assertThat(accounts.consumePairing(CredentialHash.sha256(pairingCode))).isEmpty();
            assertThat(accounts.pairingWasConsumed(CredentialHash.sha256(pairingCode))).isTrue();

            String[] outstanding = new String[4];
            for (int i = 0; i < outstanding.length; i++) {
                outstanding[i] = "integration-outstanding-" + i + "-" + UUID.randomUUID();
                assertThat(accounts.createPairing(userId, CredentialHash.sha256(outstanding[i]),
                        Instant.now().plusSeconds(300))).isPresent();
            }
            assertThat(accounts.consumePairing(CredentialHash.sha256(outstanding[0]))).isEmpty();
            for (int i = 1; i < outstanding.length; i++) {
                assertThat(accounts.consumePairing(CredentialHash.sha256(outstanding[i]))).contains(userId);
            }
        } finally {
            jdbc.update("DELETE FROM users WHERE username = 'owner'");
        }
    }
}
