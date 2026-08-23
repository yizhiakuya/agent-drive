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
                .contains("discover", "Registered operations", "POST /api/v1/tasks/clear-vectors",
                        "不得主动调用任务创建/入队接口", "真实原因",
                        "GET /api/v1/files/stats", "所有直接子目录都已查询")
                .doesNotContain("execution_mode", "必须走后台任务", "系统规定的后台任务");
    }
}
