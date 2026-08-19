package com.agentdrive.auth;

import com.agentdrive.infrastructure.persistence.MybatisCredentialAuthenticator;
import com.agentdrive.infrastructure.persistence.mapper.CredentialMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialAuthenticationTest {
    @Test
    void hashesCredentialWithoutReturningRawValue() {
        assertThat(CredentialHash.sha256("secret-token"))
                .isEqualTo("930bbdc51b6aed5c2a5678fd6e28dee7a05e8a4b643cfc0b4427c3efb86c0d94");
    }

    @Test
    void resolvesSessionOwnerBeforeDeviceOwner() {
        UUID userId = UUID.randomUUID();
        CredentialMapper mapper = new CredentialMapper() {
            @Override
            public Map<String, Object> selectSessionOwner(String credentialHash) {
                return Map.of("user_id", userId.toString());
            }

            @Override
            public Map<String, Object> selectDeviceOwner(String credentialHash) {
                return Map.of("user_id", UUID.randomUUID().toString());
            }
        };
        MybatisCredentialAuthenticator authenticator = new MybatisCredentialAuthenticator(mapper);

        Optional<AuthenticatedPrincipal> principal = authenticator.authenticate("token");

        assertThat(principal).isPresent();
        assertThat(principal.orElseThrow().userId()).isEqualTo(userId);
        assertThat(principal.orElseThrow().credentialKind())
                .isEqualTo(AuthenticatedPrincipal.CredentialKind.SESSION);
    }

    @Test
    void rejectsBlankOrUnknownCredential() {
        CredentialMapper mapper = new CredentialMapper() {
            @Override
            public Map<String, Object> selectSessionOwner(String credentialHash) {
                return null;
            }

            @Override
            public Map<String, Object> selectDeviceOwner(String credentialHash) {
                return null;
            }
        };
        MybatisCredentialAuthenticator authenticator = new MybatisCredentialAuthenticator(mapper);

        assertThat(authenticator.authenticate(" ")).isEmpty();
        assertThat(authenticator.authenticate("unknown")).isEmpty();
    }
}
