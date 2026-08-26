package com.agentdrive.api.index;

import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.auth.AuthenticatedPrincipal;
import com.agentdrive.auth.CredentialAuthenticator;
import com.agentdrive.index.IndexDomainService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndexControllerContractTest {
    @Test
    void exposesDirectVectorDeleteAsOwnerScopedBusinessOperation() {
        UUID owner = UUID.randomUUID();
        IndexDomainService index = mock(IndexDomainService.class);
        when(index.clearVectors(owner)).thenReturn(Map.of("cleared_vectors", 3, "status", "vectors_cleared"));
        WebTestClient client = WebTestClient.bindToController(new IndexController(
                index, new WebRequestPrincipalResolver(authenticator(owner)))).build();

        client.delete().uri("/api/v1/index/vectors")
                .header("Authorization", "Bearer session-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.cleared_vectors").isEqualTo(3)
                .jsonPath("$.status").isEqualTo("vectors_cleared");
    }

    @Test
    void rejectsMissingPathForDirectFileIndex() {
        UUID owner = UUID.randomUUID();
        WebTestClient client = WebTestClient.bindToController(new IndexController(
                mock(IndexDomainService.class), new WebRequestPrincipalResolver(authenticator(owner)))).build();

        client.put().uri("/api/v1/index/file")
                .header("Authorization", "Bearer session-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void exposesOwnerScopedGenericMissingIndexQuery() {
        UUID owner = UUID.randomUUID();
        IndexDomainService index = mock(IndexDomainService.class);
        when(index.missing(owner, "photos", "vector", "vision", 10, 0)).thenReturn(Map.of(
                "ok", true, "operation", "index.missing", "kind", "vector",
                "document_type", "vision", "items", java.util.List.of(Map.of("path", "photos/a.png")),
                "total_matches", 1, "returned", 1, "offset", 0, "limit", 10,
                "has_more", false));
        WebTestClient client = WebTestClient.bindToController(new IndexController(
                index, new WebRequestPrincipalResolver(authenticator(owner)))).build();

        client.get().uri("/api/v1/index/missing?prefix=photos&kind=vector&document_type=vision&limit=10&offset=0")
                .header("Authorization", "Bearer session-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.operation").isEqualTo("index.missing")
                .jsonPath("$.kind").isEqualTo("vector")
                .jsonPath("$.total_matches").isEqualTo(1)
                .jsonPath("$.items[0].path").isEqualTo("photos/a.png");
    }

    private static CredentialAuthenticator authenticator(UUID owner) {
        return credential -> "session-token".equals(credential)
                ? Optional.of(new AuthenticatedPrincipal(owner, AuthenticatedPrincipal.CredentialKind.SESSION))
                : Optional.empty();
    }
}
