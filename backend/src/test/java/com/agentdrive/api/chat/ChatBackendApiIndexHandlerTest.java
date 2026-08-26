package com.agentdrive.api.chat;

import com.agentdrive.agent.BackendApiRequest;
import com.agentdrive.index.IndexDomainService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatBackendApiIndexHandlerTest {
    @Test
    void acceptsFilesAliasForVisionBatchWithoutAFirstFailedCall() {
        UUID owner = UUID.randomUUID();
        IndexDomainService index = mock(IndexDomainService.class);
        List<String> paths = List.of("photos/a.png", "photos/b.jpg");
        when(index.indexVision(owner, paths, false)).thenReturn(Map.of(
                "status", "queued", "task_id", "task-1", "requires_terminal_check", true));
        ChatBackendApiIndexHandler handler = new ChatBackendApiIndexHandler(index);

        Map<String, Object> result = handler.dispatch("PUT /api/v1/index/vision",
                new BackendApiRequest("call", null, "PUT /api/v1/index/vision", null,
                        null, Map.of("files", paths), null), owner);

        assertThat(result).containsEntry("status", "queued");
        verify(index).indexVision(eq(owner), eq(paths), eq(false));
    }

    @Test
    void routesGenericMissingIndexQueryWithOwnerAndFilters() {
        UUID owner = UUID.randomUUID();
        IndexDomainService index = mock(IndexDomainService.class);
        when(index.missing(owner, "photos", "vector", "vision", 20, 40)).thenReturn(Map.of(
                "ok", true, "operation", "index.missing", "items", List.of(),
                "total_matches", 40, "returned", 0, "has_more", false));
        ChatBackendApiIndexHandler handler = new ChatBackendApiIndexHandler(index);

        Map<String, Object> result = handler.dispatch("GET /api/v1/index/missing",
                new BackendApiRequest("call", null, "GET /api/v1/index/missing",
                        null, Map.of("prefix", "photos", "kind", "vector", "document_type", "vision",
                                "limit", 20, "offset", 40), null, null), owner);

        assertThat(result).containsEntry("operation", "index.missing");
        verify(index).missing(eq(owner), eq("photos"), eq("vector"), eq("vision"), eq(20), eq(40));
    }
}
