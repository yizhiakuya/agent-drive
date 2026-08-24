package com.agentdrive.api.chat;

import com.agentdrive.agent.BackendApiDispatcher;
import com.agentdrive.agent.BackendApiRequest;
import com.agentdrive.agent.OperationCatalog;
import com.agentdrive.agent.OperationDefinition;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatBackendApiOperationsTest {
    @Test
    void paginatesTheRegisteredCatalogWithoutGapsOrDuplicates() {
        OperationCatalog catalog = new ChatBackendApiOperations().operationCatalog();
        Set<String> discovered = new LinkedHashSet<>();
        int offset = 0;
        int total = -1;
        boolean hasMore;

        do {
            OperationCatalog.DiscoveryPage page = catalog.discover("后端接口", offset, 20);
            if (total < 0) total = page.totalMatches();
            assertThat(page.totalMatches()).isEqualTo(total);
            assertThat(page.offset()).isEqualTo(offset);
            assertThat(page.operations()).allSatisfy(operation ->
                    assertThat(discovered.add(operation.operation())).isTrue());
            offset = page.nextOffset();
            hasMore = page.hasMore();
        } while (hasMore);

        assertThat(discovered).hasSize(total);
        assertThat(offset).isEqualTo(total);
        assertThat(total).isGreaterThan(OperationCatalog.MAX_DISCOVERY_LIMIT);
    }

    @Test
    void discoversServerSideFileStatisticsForNaturalLanguageCounting() {
        OperationCatalog catalog = new ChatBackendApiOperations().operationCatalog();

        OperationCatalog.DiscoveryPage page = catalog.discover("相册同步文件夹里边有多少文件", 0, 20);

        assertThat(page.operations()).extracting(OperationDefinition::operation)
                .contains("GET /api/v1/files/stats");
    }

    @Test
    void exposesEvidenceSearchAsAReadOnlyFileOperation() {
        OperationCatalog catalog = new ChatBackendApiOperations().operationCatalog();

        assertThat(catalog.find("GET /api/v1/files/search-content")).get()
                .satisfies(operation -> {
                    assertThat(operation.risk()).isEqualTo("green");
                    assertThat(operation.summary()).contains("证据", "相邻");
                });
    }

    @Test
    void doesNotExposeApiKeyRevealEndpointsToTheAgentCatalog() {
        OperationCatalog catalog = new ChatBackendApiOperations().operationCatalog();

        assertThat(catalog.find("POST /api/v1/config/api-key/reveal")).isEmpty();
        assertThat(catalog.find("POST /api/v1/config/embeddings/api-key/reveal")).isEmpty();
        assertThat(catalog.find("POST /api/v1/config/vision/api-key/reveal")).isEmpty();
    }

    @Test
    void keepsCredentialBearingProviderConfigurationOutOfAgentCatalog() {
        OperationCatalog catalog = new ChatBackendApiOperations().operationCatalog();

        assertThat(catalog.find("POST /api/v1/config")).isEmpty();
        assertThat(catalog.find("POST /api/v1/config/test")).isEmpty();
        assertThat(catalog.find("POST /api/v1/config/models")).isEmpty();
        assertThat(catalog.find("POST /api/v1/config/vision/models")).isEmpty();
        assertThat(catalog.find("PUT /api/v1/config/vision")).isEmpty();
        assertThat(catalog.find("PUT /api/v1/config/embeddings")).isEmpty();
        assertThat(catalog.find("POST /api/v1/vision/describe")).isPresent();
    }

    @Test
    void doesNotExposeTaskCreationOperationsToAgent() {
        OperationCatalog catalog = new ChatBackendApiOperations().operationCatalog();

        assertThat(catalog.find("DELETE /api/v1/index/stale")).isPresent();
        assertThat(catalog.find("DELETE /api/v1/index/vectors")).get()
                .satisfies(operation -> assertThat(operation.summary()).contains("直接删除"));
        assertThat(catalog.find("POST /api/v1/tasks/rebuild-index")).isEmpty();
        assertThat(catalog.find("POST /api/v1/tasks/embed-index")).isEmpty();
        assertThat(catalog.find("POST /api/v1/tasks/vision-index")).isEmpty();
        assertThat(catalog.find("POST /api/v1/tasks/clear-vectors")).isEmpty();
        assertThat(catalog.find("GET /api/v1/tasks")).isEmpty();
        assertThat(catalog.find("GET /api/v1/schedules")).isEmpty();
    }

    @Test
    void exposesDirectIndexResourceCrudOperations() {
        OperationCatalog catalog = new ChatBackendApiOperations().operationCatalog();

        assertThat(catalog.find("GET /api/v1/index")).isPresent();
        assertThat(catalog.find("GET /api/v1/index/file")).isPresent();
        assertThat(catalog.find("PUT /api/v1/index/file")).isPresent();
        assertThat(catalog.find("PUT /api/v1/index/vision")).isPresent();
        assertThat(catalog.find("PUT /api/v1/index/vectors")).isPresent();
        assertThat(catalog.find("DELETE /api/v1/index/vectors")).isPresent();
        assertThat(catalog.find("DELETE /api/v1/index/stale")).isPresent();
        assertThat(catalog.find("POST /api/v1/index/rebuild")).isPresent();
    }

    @Test
    void routesRegisteredOperationToItsOwnerScopedHandler() {
        UUID owner = UUID.randomUUID();
        BackendApiOperationHandler handler = new BackendApiOperationHandler() {
            @Override
            public Set<String> operations() {
                return Set.of("GET /api/v1/files");
            }

            @Override
            public Map<String, Object> dispatch(String operation, BackendApiRequest request, UUID userId) {
                return Map.of(
                        "operation", operation,
                        "owner", userId.toString(),
                        "path", request.queryParams().get("path"));
            }
        };
        BackendApiDispatcher dispatcher = new ChatBackendApiOperations()
                .backendApiDispatcher(List.of(handler));
        OperationDefinition operation = OperationDefinition.http("GET", "/api/v1/files", "list files");

        Map<String, Object> result = dispatcher.dispatch(operation,
                new BackendApiRequest("call", null, operation.operation(), null,
                        Map.of("path", "docs"), null, null), owner);

        assertThat(result).containsEntry("owner", owner.toString())
                .containsEntry("path", "docs");
    }

    @Test
    void rejectsMissingOwnerBeforeCallingAHandler() {
        BackendApiOperationHandler handler = new BackendApiOperationHandler() {
            @Override
            public Set<String> operations() {
                return Set.of("GET /api/v1/files");
            }

            @Override
            public Map<String, Object> dispatch(String operation, BackendApiRequest request, UUID userId) {
                throw new AssertionError("handler must not run without an owner");
            }
        };
        BackendApiDispatcher dispatcher = new ChatBackendApiOperations()
                .backendApiDispatcher(List.of(handler));

        assertThat(dispatcher.dispatch(OperationDefinition.http("GET", "/api/v1/files", "list files"),
                new BackendApiRequest("call", null, "GET /api/v1/files", null, null, null, null), null))
                .containsEntry("ok", false)
                .containsEntry("error", "missing_authenticated_owner");
    }

    @Test
    void rejectsDuplicateOperationOwnershipAtStartup() {
        BackendApiOperationHandler first = handlerFor("GET /api/v1/files");
        BackendApiOperationHandler second = handlerFor("GET /api/v1/files");

        assertThatThrownBy(() -> new ChatBackendApiOperations().backendApiDispatcher(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GET /api/v1/files");
    }

    private BackendApiOperationHandler handlerFor(String operation) {
        return new BackendApiOperationHandler() {
            @Override
            public Set<String> operations() {
                return Set.of(operation);
            }

            @Override
            public Map<String, Object> dispatch(String name, BackendApiRequest request, UUID userId) {
                return Map.of("ok", true);
            }
        };
    }
}
