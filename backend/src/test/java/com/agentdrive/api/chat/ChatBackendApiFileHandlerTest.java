package com.agentdrive.api.chat;

import com.agentdrive.agent.BackendApiRequest;
import com.agentdrive.files.FileStorageService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatBackendApiFileHandlerTest {
    @Test
    void routesRecursiveStatisticsAsOwnerScopedReadOperation() {
        UUID owner = UUID.randomUUID();
        FileStorageService files = mock(FileStorageService.class);
        Map<String, Object> expected = Map.of(
                "path", "相册同步",
                "recursive", true,
                "file_count", 777L,
                "folder_count", 97L,
                "complete", true
        );
        when(files.statistics(owner, "相册同步")).thenReturn(expected);
        ChatBackendApiFileHandler handler = new ChatBackendApiFileHandler(files);

        Map<String, Object> result = handler.dispatch(
                "GET /api/v1/files/stats",
                new BackendApiRequest("call", null, "GET /api/v1/files/stats", null,
                        Map.of("path", "相册同步"), null, null),
                owner);

        assertThat(result).isSameAs(expected);
        verify(files).statistics(eq(owner), eq("相册同步"));
    }
}
