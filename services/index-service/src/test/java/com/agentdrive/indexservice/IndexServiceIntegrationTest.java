package com.agentdrive.indexservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证独立 index schema 的文档替换和迁移清单。 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:index;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "index.internal-token=internal"
})
class IndexServiceIntegrationTest {
    @Autowired
    private IndexDocumentService service;

    @Test
    void replacesDocumentAndReportsManifest() {
        String owner = UUID.randomUUID().toString();
        String file = UUID.randomUUID().toString();
        Map<String, Object> result = service.replace(new IndexDocumentService.ReplaceRequest(
                owner, file, 1, "vision", "vision-description-v3",
                "一张粉色主题评论页面", "chunk-v1", List.of("粉色主题评论页面")));

        assertThat(result).containsEntry("ok", true).containsEntry("chunk_count", 1);
        assertThat(service.manifest(owner)).containsEntry("document_count", 1);
        assertThat(service.search(owner, "粉色", 10).get("items").toString())
                .contains("粉色主题评论页面");
    }
}
