package com.agentdrive.indexservice;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Index Service 的请求边界和 readiness 结果。 */
class IndexDocumentServiceTest {
    @Test
    void readinessDoesNotExposeDatabaseCredentials() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        Mockito.when(jdbc.queryForObject("SELECT COUNT(*) FROM index_documents", Integer.class)).thenReturn(2);
        IndexDocumentService service = new IndexDocumentService(jdbc,
                new IndexServiceProperties("internal", 10));

        assertThat(service.ready()).containsEntry("ready", true)
                .containsEntry("documents", 2)
                .doesNotContainValue("internal");
    }

    @Test
    void replacementRequestNormalizesNullContentAndChunks() {
        IndexDocumentService.ReplaceRequest request = new IndexDocumentService.ReplaceRequest(
                "owner", "file", 1, "text", "extractor-v1", null, "chunk-v1", null);

        assertThat(request.content()).isEmpty();
        assertThat(request.chunks()).isEmpty();
    }

    @Test
    void rejectsUnknownDocumentType() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        IndexDocumentService service = new IndexDocumentService(jdbc,
                new IndexServiceProperties("internal", 10));
        IndexDocumentService.ReplaceRequest request = new IndexDocumentService.ReplaceRequest(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002",
                1, "audio", "extractor-v1", "content", "chunk-v1", List.of("content"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.replace(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document_type is invalid");
    }
}
