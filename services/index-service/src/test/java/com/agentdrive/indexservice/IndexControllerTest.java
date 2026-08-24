package com.agentdrive.indexservice;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证 Index Service 未授权请求保持 401，不被通用错误处理吞成 502。 */
class IndexControllerTest {
    @Test
    void unauthorizedRequestsKeep401() throws Exception {
        IndexServiceProperties properties = new IndexServiceProperties("internal", 10);
        IndexController controller = new IndexController(properties, Mockito.mock(IndexDocumentService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/internal/v1/ready"))
                .andExpect(status().isUnauthorized());
    }
}
