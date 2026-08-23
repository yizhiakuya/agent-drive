package com.agentdrive.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exposesOnePlanToolAndNormalizesPlanSteps() throws Exception {
        PlanTool tool = new PlanTool(mapper);

        List<ToolSpecification> specifications = ToolSpecifications.toolSpecificationsFrom(PlanTool.class);
        JsonNode result = mapper.readTree(tool.execute("set", List.of(
                Map.of("text", "浏览目录", "status", "in_progress"),
                Map.of("text", "总结结果")
        )));

        assertThat(specifications).hasSize(1);
        assertThat(specifications.get(0).name()).isEqualTo("plan");
        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("action").asText()).isEqualTo("set");
        assertThat(result.path("plan")).hasSize(2);
        assertThat(result.path("plan").get(1).path("status").asText()).isEqualTo("pending");
        assertThat(tool.definitionFor(Map.of("action", "set"), new AgentToolContext(null, null, List.of())).risk())
                .isEqualTo("green");
    }

    @Test
    void rejectsInvalidPlanSteps() throws Exception {
        PlanTool tool = new PlanTool(mapper);

        JsonNode result = mapper.readTree(tool.execute("update", List.of(
                Map.of("text", "读取", "status", "unknown")
        )));

        assertThat(result.path("ok").asBoolean()).isFalse();
        assertThat(result.path("error").asText()).isEqualTo("invalid_plan");
    }
}
