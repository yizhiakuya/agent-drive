package com.agentdrive.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证独立 Identity schema 可初始化并完成 session introspection。 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:identity;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "identity.internal-token=internal"
})
class IdentityServiceIntegrationTest {
    @Autowired
    private IdentityApplicationService service;

    @Test
    void createsOwnerAndIntrospectsSession() {
        Map<String, Object> setup = service.setup("password-123");
        String token = String.valueOf(setup.get("session_token"));

        assertThat(service.status()).containsEntry("initialized", true);
        assertThat(service.introspect(token))
                .containsEntry("authenticated", true)
                .containsEntry("owner_id", setup.get("owner_id"));
        assertThat(service.logout(null, token)).containsEntry("revoked", true);
        assertThat(service.introspect(token)).containsEntry("authenticated", false);
    }
}
