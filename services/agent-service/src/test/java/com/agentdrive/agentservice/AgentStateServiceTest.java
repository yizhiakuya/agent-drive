package com.agentdrive.agentservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 回归 Agent Service transcript 写入契约。 */
class AgentStateServiceTest {
    @Test
    void runtimeAppendUserPersistsMessageContentForHistoryRestore() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID owner = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        doReturn(Map.of("owner_id", owner)).when(jdbc).query(
                contains("SELECT owner_id FROM agent_sessions"),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<Map<String, Object>>>any(),
                eq(session));

        AgentStateService service = new AgentStateService(
                jdbc, new ObjectMapper(), new AgentServiceProperties("token", 10));

        service.handle(Map.of(
                "action", "runtime.append_user",
                "session_id", session.toString(),
                "content", "请统计相册同步目录"
        ));

        org.mockito.ArgumentCaptor<Object[]> values = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("INSERT INTO agent_messages"), values.capture());
        assertThat(values.getValue()).containsExactly(
                session, owner, "user", "请统计相册同步目录", null, null, null, null, "", "");
    }
}
