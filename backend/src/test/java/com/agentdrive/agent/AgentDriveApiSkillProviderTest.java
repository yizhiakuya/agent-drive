package com.agentdrive.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDriveApiSkillProviderTest {
    @Test
    void skillDescribesApiContractWithoutImposingTaskSchedulingPolicy() {
        OperationCatalog catalog = new OperationCatalog(List.of(
                OperationDefinition.http("POST", "/api/v1/tasks/clear-vectors", "Clear vectors")));

        String instructions = new AgentDriveApiSkillProvider(catalog)
                .skills().get(0).instructions();

        assertThat(instructions)
                .contains("discover", "Registered operations", "POST /api/v1/tasks/clear-vectors")
                .doesNotContain("execution_mode", "必须走后台任务", "系统规定的后台任务");
    }
}
