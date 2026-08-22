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
import java.util.stream.IntStream;

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
        assertThat(result.path("total_matches").asInt()).isEqualTo(2);
        assertThat(result.path("returned").asInt()).isEqualTo(2);
        assertThat(result.path("has_more").asBoolean()).isFalse();
        assertThat(result.path("next_offset").asInt()).isEqualTo(2);
    }

    @Test
    void paginatesDiscoveryAndReportsTheCompleteMatchWindow() throws Exception {
        OperationCatalog pagedCatalog = new OperationCatalog(IntStream.range(0, 23)
                .mapToObj(index -> OperationDefinition.http(
                        "GET", "/api/v1/resources/%02d".formatted(index), "Registered API resource"))
                .toList());
        BackendApiTool tool = new BackendApiTool(pagedCatalog, (operation, request) -> Map.of(), mapper);

        JsonNode first = mapper.readTree(tool.executeRaw("""
                {"action":"discover","query":"api","discovery_offset":0,"discovery_limit":10}
                """, null));
        JsonNode second = mapper.readTree(tool.execute(new BackendApiRequest(
                "discover", "api", 10, 10, null, null, null, null, null)));
        JsonNode last = mapper.readTree(tool.execute(new BackendApiRequest(
                "discover", "api", 20, 10, null, null, null, null, null)));

        assertThat(first.path("operations")).hasSize(10);
        assertThat(first.path("total_matches").asInt()).isEqualTo(23);
        assertThat(first.path("returned").asInt()).isEqualTo(10);
        assertThat(first.path("offset").asInt()).isZero();
        assertThat(first.path("limit").asInt()).isEqualTo(10);
        assertThat(first.path("has_more").asBoolean()).isTrue();
        assertThat(first.path("next_offset").asInt()).isEqualTo(10);
        assertThat(first.path("operations").get(0).path("operation").asText())
                .isEqualTo("GET /api/v1/resources/00");

        assertThat(second.path("operations")).hasSize(10);
        assertThat(second.path("offset").asInt()).isEqualTo(10);
        assertThat(second.path("next_offset").asInt()).isEqualTo(20);
        assertThat(second.path("operations").get(0).path("operation").asText())
                .isEqualTo("GET /api/v1/resources/10");

        assertThat(last.path("operations")).hasSize(3);
        assertThat(last.path("returned").asInt()).isEqualTo(3);
        assertThat(last.path("offset").asInt()).isEqualTo(20);
        assertThat(last.path("has_more").asBoolean()).isFalse();
        assertThat(last.path("next_offset").asInt()).isEqualTo(23);
    }

    @Test
    void discoversGenericChineseBackendVocabulary() throws Exception {
        BackendApiTool tool = new BackendApiTool(catalog, (operation, request) -> Map.of(), mapper);

        JsonNode result = mapper.readTree(tool.execute(new BackendApiRequest(
                "discover", "查看后端接口和操作", null, null, null, null, null)));

        assertThat(result.path("total_matches").asInt()).isEqualTo(3);
        assertThat(result.path("operations")).hasSize(3);
        assertThat(result.path("has_more").asBoolean()).isFalse();
    }

    @Test
    void normalizesDiscoveryOffsetAndCapsPageSize() throws Exception {
        OperationCatalog pagedCatalog = new OperationCatalog(IntStream.range(0, 23)
                .mapToObj(index -> OperationDefinition.http(
                        "GET", "/api/v1/resources/%02d".formatted(index), "Registered API resource"))
                .toList());
        BackendApiTool tool = new BackendApiTool(pagedCatalog, (operation, request) -> Map.of(), mapper);

        JsonNode capped = mapper.readTree(tool.execute(new BackendApiRequest(
                "discover", "api", -10, 100, null, null, null, null, null)));
        JsonNode defaulted = mapper.readTree(tool.execute(new BackendApiRequest(
                "discover", "api", 0, 0, null, null, null, null, null)));

        assertThat(capped.path("offset").asInt()).isZero();
        assertThat(capped.path("limit").asInt()).isEqualTo(OperationCatalog.MAX_DISCOVERY_LIMIT);
        assertThat(capped.path("returned").asInt()).isEqualTo(OperationCatalog.MAX_DISCOVERY_LIMIT);
        assertThat(defaulted.path("limit").asInt()).isEqualTo(OperationCatalog.DISCOVERY_LIMIT);
        assertThat(defaulted.path("returned").asInt()).isEqualTo(OperationCatalog.DISCOVERY_LIMIT);
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
        assertThat(specifications.get(0).description())
                .contains("paginated", "next_offset", "has_more");
        assertThat(specifications.get(0).parameters().properties()).containsKeys(
                "action", "operation", "query_params", "discovery_offset", "discovery_limit");
    }

    @Test
    void computesWriteRiskDynamically() {
        assertThat(OperationCatalog.riskFor("GET", "/api/v1/files")).isEqualTo("green");
        assertThat(OperationCatalog.riskFor("POST", "/api/v1/config/models")).isEqualTo("green");
        assertThat(OperationCatalog.riskFor("POST", "/api/v1/config/vision/models")).isEqualTo("green");
        assertThat(OperationCatalog.riskFor("POST", "/api/v1/files/test")).isEqualTo("yellow");
        assertThat(OperationCatalog.riskFor("DELETE", "/api/v1/files")).isEqualTo("red");
    }

    @Test
    void discoveryExposesOperationShapeWithoutTaskSchedulingPolicy() throws Exception {
        OperationCatalog executionCatalog = new OperationCatalog(List.of(
                OperationDefinition.http("POST", "/api/v1/tasks/cleanup-index", "Cleanup stale index")));
        BackendApiTool tool = new BackendApiTool(executionCatalog, (operation, request) -> Map.of(), mapper);

        JsonNode result = mapper.readTree(tool.execute(new BackendApiRequest(
                "discover", "cleanup index", null, null, null, null, null)));

        assertThat(result.path("operations").get(0).path("operation").asText())
                .isEqualTo("POST /api/v1/tasks/cleanup-index");
        assertThat(result.path("operations").get(0).has("execution_mode")).isFalse();
    }
}
