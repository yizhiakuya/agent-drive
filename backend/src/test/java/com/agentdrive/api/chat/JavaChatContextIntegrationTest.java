package com.agentdrive.api.chat;

import com.agentdrive.agent.BackendApiTool;
import com.agentdrive.agent.OperationCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles({"db", "java-chat"})
@EnabledIfEnvironmentVariable(named = "AGENT_DRIVE_JDBC_TEST_URL", matches = ".+")
class JavaChatContextIntegrationTest {
    @Autowired
    private ChatRuntime runtime;

    @Autowired
    private BackendApiTool backendApiTool;

    @Autowired
    private OperationCatalog operations;

    @Autowired
    private com.agentdrive.agent.ReadSkillTool readSkillTool;

    @Autowired
    private com.agentdrive.skills.SkillRegistry skillRegistry;

    @Test
    void loadsPersistentRuntimeAndOwnerScopedConfigOperation() {
        assertThat(runtime).isInstanceOf(LangChainAgentRuntime.class);
        assertThat(operations.find("GET /api/v1/config")).isPresent();
        assertThat(operations.find("GET /api/v1/config/status")).isPresent();
        assertThat(operations.find("POST /api/v1/config")).isPresent();
        assertThat(operations.find("POST /api/v1/config/test")).isPresent();
        assertThat(operations.find("POST /api/v1/config/models")).isPresent();
        assertThat(operations.find("POST /api/v1/config/vision/models")).isPresent();
        assertThat(operations.find("PUT /api/v1/config/embeddings")).isPresent();
        assertThat(operations.find("INTERNAL write_text")).isPresent();
        assertThat(operations.find("GET /api/v1/automation/latest")).isPresent();
        assertThat(operations.find("GET /api/v1/sessions")).isPresent();
        assertThat(operations.find("GET /api/v1/sessions/{sessionId}")).isPresent();
        assertThat(operations.find("POST /api/v1/sessions/{sessionId}/summarize")).isPresent();
        assertThat(operations.find("DELETE /api/v1/sessions/{sessionId}")).isPresent();
        assertThat(operations.find("GET /api/v1/files")).isPresent();
        assertThat(operations.find("GET /api/v1/devices")).isPresent();
        assertThat(operations.find("POST /api/v1/devices/register")).isPresent();
        assertThat(operations.find("GET /api/v1/tasks")).isPresent();
        assertThat(operations.find("POST /api/v1/tasks/rebuild-index")).isPresent();
        assertThat(operations.find("POST /api/v1/tasks/embed-index")).isPresent();
        assertThat(operations.find("GET /api/v1/schedules")).isPresent();
        assertThat(operations.find("PUT /api/v1/schedules/{name}")).isPresent();
        assertThat(operations.find("DELETE /api/v1/schedules/{name}")).isPresent();
        assertThat(operations.find("POST /api/v1/files/delete")).isPresent();
        assertThat(operations.find("GET /api/v1/skills")).isPresent();
        assertThat(operations.find("PUT /api/v1/skills/{name}")).isPresent();
        assertThat(readSkillTool).isNotNull();
        assertThat(skillRegistry).isNotNull();
        assertThat(backendApiTool.definitionFor(new com.agentdrive.agent.BackendApiRequest(
                "call", null, "GET /api/v1/config", null, null, null, null
        ))).isNotNull();
    }

    @Test
    void dispatchesOwnerScopedConfigStatusAsJson() {
        String result = backendApiTool.execute(new com.agentdrive.agent.BackendApiRequest(
                "call", null, "GET /api/v1/config/status", null, null, null, null
        ), java.util.UUID.randomUUID());

        assertThat(result).contains("\"ok\":true").contains("\"configured\":false");
    }
}
