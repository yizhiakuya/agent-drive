package com.agentdrive.api.chat;

import com.agentdrive.agent.ChatTranscriptStore;
import com.agentdrive.files.FileStorageException;
import com.agentdrive.files.FileStorageService;
import com.agentdrive.infrastructure.SensitiveDataRedactor;
import com.agentdrive.skills.SkillDefinition;
import com.agentdrive.skills.SkillPage;
import com.agentdrive.skills.SkillRegistry;
import com.agentdrive.skills.SkillSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
                .containsExactly("agent-drive-system-prompt", "AGENT.md", "USER.md", "MEMORY.md", "skill-catalog");
        assertThat(contexts.get(0).userMessage()).isFalse();
        assertThat(contexts.get(1).content()).contains("[REDACTED]").doesNotContain("sk-secret-value");
        assertThat(contexts.get(4).content())
                .contains("agent-drive-api", "&lt;registered&gt;", "&amp; tools", "read_skill")
                .doesNotContain("Use <registered>");
    }

    @Test
    void readsSelectedFileAsOwnerScopedContextAndPrescribesClickableReference() {
        UUID owner = UUID.randomUUID();
        SkillRegistry skills = mock(SkillRegistry.class);
        FileStorageService files = mock(FileStorageService.class);
        when(files.content(owner, "notes/today.md", 16 * 1024)).thenReturn(Map.of(
                "content", "付款节点 api_key=sk-secret-value", "truncated", true));
        when(files.list(owner, "notes/today.md"))
                .thenThrow(new FileStorageException(400, "目标不是目录"));
        when(files.info(owner, "notes/today.md")).thenReturn(Map.of("path", "notes/today.md"));
        when(skills.discover(owner, "", false, 0, 50))
                .thenReturn(new SkillPage(List.of(), 0, 0, 0, 50, false, 0));

        List<ChatContext> contexts = new DefaultChatContextProvider(
                skills, files, "").contexts(owner, List.of("notes/today.md"));

        ChatContext attachment = contexts.get(contexts.size() - 1);
        assertThat(attachment.source()).isEqualTo("notes/today.md");
        assertThat(attachment.kind()).isEqualTo("file-attachment");
        assertThat(attachment.userMessage()).isTrue();
        assertThat(attachment.content()).contains("付款节点", "[REDACTED]", "[[file:notes/today.md]]")
                .doesNotContain("sk-secret-value", "Use <registered>");
    }

    @Test
    void reusesPreviouslyReadSkillBodyWithoutAskingModelToReadItAgain() {
        UUID owner = UUID.randomUUID();
        SkillRegistry skills = mock(SkillRegistry.class);
        FileStorageService files = mock(FileStorageService.class);
        ChatTranscriptStore transcript = mock(ChatTranscriptStore.class);
        when(files.content(owner, "Agent/AGENT.md", 16 * 1024))
                .thenThrow(new FileStorageException(404, "not found"));
        when(files.content(owner, "Agent/USER.md", 16 * 1024))
                .thenThrow(new FileStorageException(404, "not found"));
        when(files.content(owner, "Agent/MEMORY.md", 16 * 1024))
                .thenThrow(new FileStorageException(404, "not found"));
        when(skills.discover(owner, "", false, 0, 50)).thenReturn(new SkillPage(List.of(
                summary("agent-drive-api", "Registered operations")
        ), 1, 1, 0, 50, false, 1));
        when(transcript.loadedSkillNames("session-1")).thenReturn(List.of("agent-drive-api"));
        when(skills.read(owner, "agent-drive-api", false)).thenReturn(Optional.of(new SkillDefinition(
                "agent-drive-api", "Registered operations", "Use exact registered operations.", true,
                "builtin", 1, null, null)));

        DefaultChatContextProvider provider = new DefaultChatContextProvider(
                skills, files, "", new SensitiveDataRedactor(), transcript);

        List<ChatContext> contexts = provider.contexts(owner, "session-1", List.of());

        assertThat(contexts).extracting(ChatContext::source)
                .containsExactly("agent-drive-system-prompt", "AGENT.md", "USER.md", "MEMORY.md",
                        "skill:agent-drive-api", "skill-catalog");
        assertThat(contexts.get(4).kind()).isEqualTo("skill-instructions");
        assertThat(contexts.get(4).content()).contains("Use exact registered operations.");
        assertThat(contexts.get(5).content())
                .contains("agent-drive-api", "[已加载]", "不要再次调用 `read_skill`");
    }

    @Test
    void compilesPersonalDocumentsOutOfSimpleReadOnlyFileRequests() {
        UUID owner = UUID.randomUUID();
        SkillRegistry skills = mock(SkillRegistry.class);
        FileStorageService files = mock(FileStorageService.class);
        when(files.content(owner, "Agent/AGENT.md", 16 * 1024))
                .thenReturn(Map.of("content", "File Concierge", "truncated", false));
        when(skills.discover(owner, "", false, 0, 50))
                .thenReturn(new SkillPage(List.of(), 0, 0, 0, 50, false, 0));

        DefaultChatContextProvider provider = new DefaultChatContextProvider(skills, files, "system");

        assertThat(provider.contexts(owner, "session-read", List.of(), "相册同步有多少文件"))
                .extracting(ChatContext::source)
                .containsExactly("agent-drive-system-prompt", "AGENT.md", "skill-catalog");
        assertThat(provider.contexts(owner, "session-write", List.of(), "整理资料目录中的合同"))
                .extracting(ChatContext::source)
                .containsExactly("agent-drive-system-prompt", "AGENT.md", "USER.md", "MEMORY.md", "skill-catalog");
    }

    private static SkillSummary summary(String name, String description) {
        return new SkillSummary(name, description, true, "builtin", 1, null);
    }
}
