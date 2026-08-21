package com.agentdrive.api.chat;

import com.agentdrive.files.FileStorageException;
import com.agentdrive.files.FileStorageService;
import com.agentdrive.skills.SkillPage;
import com.agentdrive.skills.SkillRegistry;
import com.agentdrive.skills.SkillSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultChatContextProviderTest {
    @Test
    void assemblesRedactedAgentDocumentsAndCompleteSkillCatalog() {
        UUID owner = UUID.randomUUID();
        SkillRegistry skills = mock(SkillRegistry.class);
        FileStorageService files = mock(FileStorageService.class);
        when(files.content(owner, "Agent/AGENT.md", 16 * 1024)).thenReturn(Map.of(
                "content", "Use the saved workflow. api_key=sk-secret-value", "truncated", false));
        when(files.content(owner, "Agent/USER.md", 16 * 1024))
                .thenThrow(new FileStorageException(404, "not found"));
        when(files.content(owner, "Agent/MEMORY.md", 16 * 1024)).thenReturn(Map.of(
                "content", "Prefer concise replies.", "truncated", false));
        when(skills.discover(owner, "", false, 0, 50)).thenReturn(new SkillPage(List.of(
                summary("agent-drive-api", "Use <registered> APIs & tools"),
                summary("skill-authoring", "Create skills")
        ), 2, 2, 0, 50, false, 2));

        DefaultChatContextProvider provider = new DefaultChatContextProvider(
                skills, files, AgentSystemPrompt.normalize("Configured prompt"));

        List<ChatContext> contexts = provider.contexts(owner);

        assertThat(contexts).extracting(ChatContext::source)
                .containsExactly("agent-drive-system-prompt", "AGENT.md", "MEMORY.md", "skill-catalog");
        assertThat(contexts.get(0).userMessage()).isFalse();
        assertThat(contexts.get(1).content()).contains("[REDACTED]").doesNotContain("sk-secret-value");
        assertThat(contexts.get(3).content())
                .contains("agent-drive-api", "&lt;registered&gt;", "&amp; tools", "read_skill")
                .doesNotContain("Use <registered>");
    }

    private static SkillSummary summary(String name, String description) {
        return new SkillSummary(name, description, true, "builtin", 1, null);
    }
}
