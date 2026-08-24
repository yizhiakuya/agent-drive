package com.agentdrive.identity;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 Identity Service 的 owner 初始化、登录和 introspection 语义。 */
class IdentityApplicationServiceTest {
    @Test
    void setupIssuesSessionAndIntrospectionPreservesOwner() {
        IdentityStore store = Mockito.mock(IdentityStore.class);
        PasswordHasher passwords = Mockito.mock(PasswordHasher.class);
        UUID owner = UUID.randomUUID();
        Mockito.when(store.createOwner("hash")).thenReturn(Optional.of(owner));
        Mockito.when(passwords.hash("password-123")).thenReturn("hash");
        IdentityApplicationService service = new IdentityApplicationService(store, passwords);

        Map<String, Object> result = service.setup("password-123");

        assertThat(result).containsEntry("ok", true).containsEntry("owner_id", owner.toString());
        Mockito.verify(store).createSession(Mockito.eq(owner), Mockito.anyString(), Mockito.any(Instant.class));
    }

    @Test
    void loginRejectsWrongPassword() {
        IdentityStore store = Mockito.mock(IdentityStore.class);
        PasswordHasher passwords = Mockito.mock(PasswordHasher.class);
        UUID owner = UUID.randomUUID();
        Mockito.when(store.owner()).thenReturn(Optional.of(new IdentityStore.Owner(owner, "hash")));
        Mockito.when(passwords.matches("wrong", "hash")).thenReturn(false);
        IdentityApplicationService service = new IdentityApplicationService(store, passwords);

        assertThatThrownBy(() -> service.login("wrong"))
                .isInstanceOf(IdentityApplicationService.IdentityException.class)
                .satisfies(error -> assertThat(((IdentityApplicationService.IdentityException) error).status()).isEqualTo(401));
    }
}
