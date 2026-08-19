package com.agentdrive.index;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 语义搜索服务的查询向量、owner 范围和结果映射测试。 */
class SemanticSearchServiceTest {
    @Test
    void mapsBestChunkRowsToFileSearchItems() {
        UUID owner = UUID.randomUUID();
        EmbeddingService embeddings = mock(EmbeddingService.class);
        IndexStore index = mock(IndexStore.class);
        when(embeddings.embedQuery(owner, "付款和验收"))
                .thenReturn(new EmbeddingService.QueryEmbedding("[0.1,0.2]", "fingerprint"));
        when(index.semanticSearch(owner, "fingerprint", "[0.1,0.2]", "documents", 100))
                .thenReturn(List.of(Map.of(
                        "path", "documents/contract.txt",
                        "size", 128,
                        "mtime", 1750000000.0,
                        "chunk_index", 2,
                        "search_score", 0.91,
                        "search_snippet", "付款节点和验收条件"
                )));

        Map<String, Object> result = new SemanticSearchService.Jina(embeddings, index)
                .search(owner, "documents", "付款和验收");

        assertThat(result).containsEntry("mode", "semantic").containsEntry("query", "付款和验收");
        assertThat(result.get("items")).asList().singleElement().satisfies(item ->
                assertThat(item).isInstanceOf(Map.class));
        Map<?, ?> item = (Map<?, ?>) ((List<?>) result.get("items")).get(0);
        assertThat(item.get("name")).isEqualTo("contract.txt");
        assertThat(item.get("path")).isEqualTo("documents/contract.txt");
        assertThat(item.get("search_score")).isEqualTo(0.91);
        assertThat(item.get("search_snippet")).isEqualTo("付款节点和验收条件");
        verify(index).semanticSearch(eq(owner), eq("fingerprint"), eq("[0.1,0.2]"), eq("documents"), eq(100));
    }
}
