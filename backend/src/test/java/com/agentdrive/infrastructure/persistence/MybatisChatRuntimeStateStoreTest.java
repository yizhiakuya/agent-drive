package com.agentdrive.infrastructure.persistence;

import com.agentdrive.infrastructure.persistence.mapper.ChatRuntimeStateMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MybatisChatRuntimeStateStoreTest {
    @Test
    void normalizesLoadedSkillNamesFromTranscriptStore() {
        ChatRuntimeStateMapper mapper = mock(ChatRuntimeStateMapper.class);
        String sessionId = "acdc9ef2-74ad-467d-8829-66682d411589";
        when(mapper.selectLoadedSkillNames(sessionId))
                .thenReturn(Arrays.asList(" agent-drive-api ", "agent-drive-api", "", null));
        MybatisChatRuntimeStateStore store = new MybatisChatRuntimeStateStore(mapper, new ObjectMapper());

        assertThat(store.loadedSkillNames(sessionId)).containsExactly("agent-drive-api");
        assertThat(store.loadedSkillNames("not-a-uuid")).isEmpty();
        verify(mapper).selectLoadedSkillNames(sessionId);
    }

    @Test
    void loadsOwnerScopedModelHistoryAndInvalidatesReplays() {
        ChatRuntimeStateMapper mapper = mock(ChatRuntimeStateMapper.class);
        UUID owner = UUID.randomUUID();
        String session = UUID.randomUUID().toString();
        when(mapper.selectModelHistory(owner.toString(), session, 80))
                .thenReturn(List.of(Map.of("role", "user", "content", "hello")));
        MybatisChatRuntimeStateStore store = new MybatisChatRuntimeStateStore(mapper, new ObjectMapper());

        assertThat(store.loadHistory(owner, session, 80)).isEqualTo(Optional.of(
                List.of(Map.of("role", "user", "content", "hello"))));
        store.invalidate(session);
        verify(mapper).deleteToolReplays(session);
    }
}
