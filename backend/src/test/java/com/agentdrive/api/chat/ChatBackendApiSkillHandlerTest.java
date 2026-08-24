package com.agentdrive.api.chat;

import com.agentdrive.agent.BackendApiRequest;
import com.agentdrive.skills.SkillDefinition;
import com.agentdrive.skills.SkillPage;
import com.agentdrive.skills.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatBackendApiSkillHandlerTest {
    @Test
    void routesListAndSaveToOwnerRegistry() {
        UUID owner = UUID.randomUUID();
        SkillRegistry registry = mock(SkillRegistry.class);
        ChatBackendApiSkillHandler handler = new ChatBackendApiSkillHandler(registry);
        SkillPage page = new SkillPage(List.of(), 0, 0, 0, 20, false, 0);
        SkillDefinition saved = new SkillDefinition(
                "weekly-report", "周报", "生成周报", true, "custom", 1, 1.0, 1.0);
        when(registry.discover(owner, "report", false, 0, 20)).thenReturn(page);
        when(registry.save(owner, "weekly-report", "周报", "生成周报", true)).thenReturn(saved);

        Map<String, Object> listed = handler.dispatch("GET /api/v1/skills", new BackendApiRequest(
                "call", null, "GET /api/v1/skills", null,
                Map.of("q", "report", "include_disabled", true, "offset", 0, "limit", 20),
                null, null), owner);
        Map<String, Object> created = handler.dispatch("PUT /api/v1/skills/{name}", new BackendApiRequest(
                "call", null, "PUT /api/v1/skills/{name}", Map.of("name", "weekly-report"), null,
                Map.of("description", "周报", "instructions", "生成周报", "enabled", true), null), owner);

        assertThat(listed).containsEntry("page", page);
        assertThat(created).containsEntry("skill", saved);
        verify(registry).save(owner, "weekly-report", "周报", "生成周报", true);
        org.mockito.Mockito.verify(registry).discover(owner, "report", false, 0, 20);
    }
}
