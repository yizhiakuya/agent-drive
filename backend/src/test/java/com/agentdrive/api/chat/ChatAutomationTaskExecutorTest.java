package com.agentdrive.api.chat;

import com.agentdrive.files.FileStorageService;
import com.agentdrive.auth.ConversationSessionService;
import com.agentdrive.progress.TaskProgressReporter;
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

    @Test
    void runsBackgroundChatFromOwnerSessionAndDoesNotCarryInlineImages() {
        ChatRuntime runtime = mock(ChatRuntime.class);
        FileStorageService files = mock(FileStorageService.class);
        ConversationSessionService sessions = mock(ConversationSessionService.class);
        TaskProgressReporter progress = mock(TaskProgressReporter.class);
        UUID owner = UUID.randomUUID();
        String sessionId = UUID.randomUUID().toString();
        when(sessions.getOwned(owner, sessionId)).thenReturn(new ConversationSessionService.SessionDetails(
                Map.of(), List.of(
                        Map.of("role", "user", "content", "之前的请求"),
                        Map.of("role", "tool_call", "content", "隐藏工具"))));
        when(runtime.complete(any(ChatRequest.class))).thenReturn(Mono.just(new ChatResponse(
                "已完成", List.of(), 1, 8L, null, sessionId, false, "chat", List.of(), Map.of(), Map.of(), false)));
        ChatAutomationTaskExecutor executor = new ChatAutomationTaskExecutor(runtime, files, sessions);

        Map<String, Object> result = executor.execute(owner, Map.of(
                "session_id", sessionId, "message", "继续处理", "file_context", List.of("notes/today.md")), progress);

        assertThat(result).containsEntry("ok", true).containsEntry("session_id", sessionId);
        org.mockito.ArgumentCaptor<ChatRequest> request = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        verify(runtime).complete(request.capture());
        assertThat(request.getValue().sessionId()).isEqualTo(sessionId);
        assertThat(request.getValue().authenticatedUserId()).isEqualTo(owner);
        assertThat(request.getValue().fileContext()).containsExactly("notes/today.md");
        assertThat(request.getValue().inlineImages()).isEmpty();
        verify(progress).reportNow(0, 0, "聊天任务：开始执行");
        verify(progress).reportNow(1, 1, "聊天任务：执行完成");
        executor.shutdown();
    }
}
