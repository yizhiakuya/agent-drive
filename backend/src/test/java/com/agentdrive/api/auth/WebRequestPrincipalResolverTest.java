package com.agentdrive.api.auth;

import com.agentdrive.auth.AuthenticatedPrincipal;
import com.agentdrive.auth.CredentialAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebRequestPrincipalResolverTest {
    @Test
    void cookieWinsOverBearerForWebCompatibility() {
        UUID userId = UUID.randomUUID();
        CredentialAuthenticator authenticator = credential -> {
            if ("cookie-token".equals(credential)) {
                return Optional.of(new AuthenticatedPrincipal(
                        userId, AuthenticatedPrincipal.CredentialKind.SESSION
                ));
            }
            return Optional.empty();
        };
        WebRequestPrincipalResolver resolver = new WebRequestPrincipalResolver(authenticator);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/chat")
                        .cookie(new HttpCookie("agentdrive_session", "cookie-token"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
                        .build()
        );

        assertThat(resolver.resolve(exchange).block().userId()).isEqualTo(userId);
    }

    @Test
    void fallsBackToBearerWhenCookieIsStale() {
        UUID userId = UUID.randomUUID();
        CredentialAuthenticator authenticator = credential ->
                "bearer-token".equals(credential)
                        ? Optional.of(new AuthenticatedPrincipal(
                                userId, AuthenticatedPrincipal.CredentialKind.DEVICE
                        ))
                        : Optional.empty();
        WebRequestPrincipalResolver resolver = new WebRequestPrincipalResolver(authenticator);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/files")
                        .cookie(new HttpCookie("agentdrive_session", "stale-cookie"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer bearer-token")
                        .build()
        );

        assertThat(resolver.resolve(exchange).block().userId()).isEqualTo(userId);
    }

    @Test
    void acceptsCaseInsensitiveBearerScheme() {
        UUID userId = UUID.randomUUID();
        CredentialAuthenticator authenticator = credential ->
                "bearer-token".equals(credential)
                        ? Optional.of(new AuthenticatedPrincipal(
                                userId, AuthenticatedPrincipal.CredentialKind.DEVICE
                        ))
                        : Optional.empty();
        WebRequestPrincipalResolver resolver = new WebRequestPrincipalResolver(authenticator);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/files").header(
                        HttpHeaders.AUTHORIZATION, "bEaReR bearer-token"
                ).build()
        );

        assertThat(resolver.resolve(exchange).block().credentialKind())
                .isEqualTo(AuthenticatedPrincipal.CredentialKind.DEVICE);
    }

    @Test
    void rejectsMissingCredentials() {
        WebRequestPrincipalResolver resolver = new WebRequestPrincipalResolver(credential -> Optional.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/chat").build()
        );

        assertThatThrownBy(() -> resolver.resolve(exchange).block())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authentication required");
    }
}
