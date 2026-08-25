package com.agentdrive.agentservice;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证 Agent Service 的内部 token 边界。 */
class AgentStateControllerTest {
    @Test
    void readinessRequiresInternalToken() throws Exception {
        AgentServiceProperties properties = new AgentServiceProperties("internal", 10);
        AgentStateController controller = new AgentStateController(properties, Mockito.mock(AgentStateService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/internal/v1/ready")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/ready").header("X-Agent-Service-Token", "internal"))
                .andExpect(status().isOk());
    }
}
