package com.agentdrive.infrastructure.persistence;

import com.agentdrive.infrastructure.persistence.mapper.ChatRuntimeStateMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Arrays;

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
}
