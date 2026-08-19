package com.agentdrive.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackendApiToolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OperationCatalog catalog = new OperationCatalog(List.of(
            OperationDefinition.http("GET", "/api/v1/files", "List files"),
            OperationDefinition.http("POST", "/api/v1/files/upload", "Upload file"),
            OperationDefinition.http("POST", "/api/v1/config/models", "Probe models"),
            OperationDefinition.internal("search_content", "Search indexed content", "green")
    ));

    @Test
    void rejectsProtectedHttpRoutes() {
        assertThatThrownBy(() -> OperationDefinition.http("GET", "/api/v1/auth/status", "auth"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OperationDefinition.http("GET", "https://example.com/files", "external"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void discoversChineseAliasesAndCapsResults() throws Exception {
        BackendApiTool tool = new BackendApiTool(catalog, (operation, request) -> Map.of(), mapper);

        JsonNode result = mapper.readTree(tool.execute(new BackendApiRequest("discover", "上传文件", null, null, null, null, null)));

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("operations").size()).isGreaterThan(0);
        assertThat(result.path("operations").findValuesAsText("operation"))
                .contains("POST /api/v1/files/upload");
    }

    @Test
    void callsOnlyAnExactRegisteredOperation() throws Exception {
        BackendApiTool tool = new BackendApiTool((new OperationCatalog(List.of(
                OperationDefinition.http("GET", "/api/v1/files", "List files")
        ))), (operation, request) -> Map.of("items", 2), mapper);

        JsonNode result = mapper.readTree(tool.execute(new BackendApiRequest(
                "call", null, "GET /api/v1/files", null, null, null, null)));

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("risk").asText()).isEqualTo("green");
        assertThat(result.path("result").path("items").asInt()).isEqualTo(2);
    }

    @Test
    void rejectsQueryParameterPlacedInPathParamsInsteadOfSilentlyListingRoot() throws Exception {
        AtomicReference<Boolean> dispatched = new AtomicReference<>(false);
        BackendApiTool tool = new BackendApiTool(catalog, (operation, request) -> {
            dispatched.set(true);
            return Map.of("items", 2);
        }, mapper);

        JsonNode result = mapper.readTree(tool.execute(new BackendApiRequest(
                "call", null, "GET /api/v1/files", Map.of("path", "Agent"), null, null, null)));

        assertThat(result.path("ok").asBoolean()).isFalse();
        assertThat(result.path("error").asText()).isEqualTo("invalid_parameter_location");
        assertThat(result.path("message").asText()).contains("query_params");
        assertThat(result.path("details").path("expected_path_params")).isEmpty();
        assertThat(dispatched).hasValue(false);
    }

    @Test
    void acceptsExactlyThePathPlaceholdersDeclaredByOperation() throws Exception {
        OperationCatalog pathCatalog = new OperationCatalog(List.of(
                OperationDefinition.http("GET", "/api/v1/sessions/{sessionId}", "Get session")
        ));
        BackendApiTool tool = new BackendApiTool(pathCatalog, (operation, request) -> Map.of("ok", true), mapper);

        JsonNode result = mapper.readTree(tool.execute(new BackendApiRequest(
                "call", null, "GET /api/v1/sessions/{sessionId}",
                Map.of("sessionId", "session-1"), null, null, null)));

        assertThat(result.path("ok").asBoolean()).isTrue();
    }

    @Test
    void forwardsOwnerOnlyThroughInternalDispatcherOverload() throws Exception {
        UUID owner = UUID.randomUUID();
        AtomicReference<UUID> received = new AtomicReference<>();
        BackendApiDispatcher dispatcher = new BackendApiDispatcher() {
            @Override
            public Map<String, Object> dispatch(OperationDefinition operation, BackendApiRequest request) {
                return Map.of("owner", "legacy");
            }

            @Override
            public Map<String, Object> dispatch(OperationDefinition operation,
                                                BackendApiRequest request,
                                                UUID userId) {
                received.set(userId);
                return Map.of("owner", userId.toString());
            }
        };
        BackendApiTool tool = new BackendApiTool(catalog, dispatcher, mapper);

        JsonNode result = mapper.readTree(tool.execute(new BackendApiRequest(
                "call", null, "GET /api/v1/files", null, null, null, null), owner));

        assertThat(received).hasValue(owner);
        assertThat(result.path("result").path("owner").asText()).isEqualTo(owner.toString());
    }

    @Test
    void returnsSuggestionsForUnknownOperation() throws Exception {
        BackendApiTool tool = new BackendApiTool(catalog, (operation, request) -> Map.of(), mapper);

        JsonNode result = mapper.readTree(tool.execute(new BackendApiRequest(
                "call", null, "GET /api/v1/file", null, null, null, null)));

        assertThat(result.path("ok").asBoolean()).isFalse();
        assertThat(result.path("error").asText()).isEqualTo("unknown_operation");
        assertThat(result.path("suggestions").size()).isGreaterThan(0);
    }

    @Test
    void exposesOneStructuredBackendApiTool() {
        List<ToolSpecification> specifications = ToolSpecifications.toolSpecificationsFrom(BackendApiTool.class);

        assertThat(specifications).hasSize(1);
        assertThat(specifications.get(0).name()).isEqualTo("backend_api");
        assertThat(specifications.get(0).parameters().properties()).containsKeys("action", "operation", "query_params");
    }

    @Test
    void computesWriteRiskDynamically() {
        assertThat(OperationCatalog.riskFor("GET", "/api/v1/files")).isEqualTo("green");
        assertThat(OperationCatalog.riskFor("POST", "/api/v1/config/models")).isEqualTo("green");
        assertThat(OperationCatalog.riskFor("POST", "/api/v1/config/vision/models")).isEqualTo("green");
        assertThat(OperationCatalog.riskFor("POST", "/api/v1/files/test")).isEqualTo("yellow");
        assertThat(OperationCatalog.riskFor("DELETE", "/api/v1/files")).isEqualTo("red");
    }
}
