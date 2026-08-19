package com.agentdrive.api.chat;

import com.agentdrive.files.FileStorageService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatAutomationTaskExecutorTest {
    @Test
    void runsPromptAndWritesOwnerScopedReport() {
        ChatRuntime runtime = mock(ChatRuntime.class);
        FileStorageService files = mock(FileStorageService.class);
        UUID owner = UUID.randomUUID();
        when(runtime.complete(any(ChatRequest.class))).thenReturn(Mono.just(new ChatResponse(
                "整理完成", List.of(), 2, 12L, null, "session", false, "task", List.of(), Map.of(), Map.of(), false)));
        ChatAutomationTaskExecutor executor = new ChatAutomationTaskExecutor(runtime, files);

        Map<String, Object> result = executor.execute(owner, Map.of("rules", List.of("整理下载目录")));

        assertThat(result).containsEntry("ok", true).containsEntry("steps", 2);
        verify(files).writeText(eq(owner), eq("Agent/notes/自动化报告-" + java.time.LocalDate.now() + ".md"),
                org.mockito.ArgumentMatchers.contains("整理完成"), eq(true));
    }
}
