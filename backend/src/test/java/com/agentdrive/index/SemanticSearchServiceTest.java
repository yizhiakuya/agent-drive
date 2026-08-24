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

    @Test
    void groupsMultipleEvidenceChunksAndNeighborContextWithoutCrossingOwnerBoundary() {
        UUID owner = UUID.randomUUID();
        EmbeddingService embeddings = mock(EmbeddingService.class);
        IndexStore index = mock(IndexStore.class);
        when(embeddings.embedQuery(owner, "付款和验收"))
                .thenReturn(new EmbeddingService.QueryEmbedding("[0.1,0.2]", "fingerprint"));
        when(index.semanticEvidence(eq(owner), eq("fingerprint"), eq("[0.1,0.2]"),
                eq("documents"), eq(9), eq(1), eq(0.7)))
                .thenReturn(List.of(
                        Map.ofEntries(Map.entry("file_id", "file-1"), Map.entry("path", "documents/contract.txt"),
                                Map.entry("source_revision", 3L), Map.entry("vector_type", "text"),
                                Map.entry("match_chunk_index", 2), Map.entry("chunk_version", "chunk-v1"),
                                Map.entry("match_content", "付款节点为 30%"), Map.entry("search_score", 0.91),
                                Map.entry("result_rank", 1), Map.entry("context_chunk_index", 1),
                                Map.entry("context_content", "合同总价及签署日期")),
                        Map.ofEntries(Map.entry("file_id", "file-1"), Map.entry("path", "documents/contract.txt"),
                                Map.entry("source_revision", 3L), Map.entry("vector_type", "text"),
                                Map.entry("match_chunk_index", 2), Map.entry("chunk_version", "chunk-v1"),
                                Map.entry("match_content", "付款节点为 30%"), Map.entry("search_score", 0.91),
                                Map.entry("result_rank", 1), Map.entry("context_chunk_index", 2),
                                Map.entry("context_content", "付款节点为 30%")),
                        Map.ofEntries(Map.entry("file_id", "file-1"), Map.entry("path", "documents/contract.txt"),
                                Map.entry("source_revision", 3L), Map.entry("vector_type", "text"),
                                Map.entry("match_chunk_index", 2), Map.entry("chunk_version", "chunk-v1"),
                                Map.entry("match_content", "付款节点为 30%"), Map.entry("search_score", 0.91),
                                Map.entry("result_rank", 1), Map.entry("context_chunk_index", 3),
                                Map.entry("context_content", "验收后支付尾款"))));

        Map<String, Object> result = new SemanticSearchService.Jina(embeddings, index)
                .searchEvidence(owner, "documents", "付款和验收", 2, 1, 0.7,
                        "all", null, null);

        assertThat(result).containsEntry("mode", "semantic_evidence")
                .containsEntry("trust", "untrusted_data")
                .containsEntry("evidence_status", "ok")
                .containsEntry("neighbors", 1)
                .containsEntry("has_more", false);
        Map<?, ?> item = (Map<?, ?>) ((List<?>) result.get("results")).get(0);
        assertThat(item.get("path")).isEqualTo("documents/contract.txt");
        assertThat(item.get("source_revision")).isEqualTo(3L);
        assertThat(item.get("chunk_index")).isEqualTo(2);
        assertThat(item.get("text")).isEqualTo("付款节点为 30%");
        List<?> contexts = (List<?>) item.get("neighbors");
        assertThat(contexts).hasSize(2);
        assertThat(((Map<?, ?>) contexts.get(0)).get("chunk_index")).isEqualTo(1);
        assertThat(((Map<?, ?>) contexts.get(1)).get("chunk_index")).isEqualTo(3);
        verify(index).semanticEvidence(eq(owner), eq("fingerprint"), eq("[0.1,0.2]"),
                eq("documents"), eq(9), eq(1), eq(0.7));
    }

    @Test
    void marksAnEmptyEvidenceWindowAsNotMatchedOrNotIndexed() {
        UUID owner = UUID.randomUUID();
        EmbeddingService embeddings = mock(EmbeddingService.class);
        IndexStore index = mock(IndexStore.class);
        when(embeddings.embedQuery(owner, "合同编号"))
                .thenReturn(new EmbeddingService.QueryEmbedding("[0.1,0.2]", "fingerprint"));
        when(index.semanticEvidence(eq(owner), eq("fingerprint"), eq("[0.1,0.2]"),
                eq(null), eq(5), eq(0), eq(null)))
                .thenReturn(List.of());

        Map<String, Object> result = new SemanticSearchService.Jina(embeddings, index)
                .searchEvidence(owner, "", "合同编号", 1, 0, null,
                        "all", null, null);

        assertThat(result).containsEntry("evidence_status", "no_match_or_not_indexed");
        assertThat(result.get("results")).asList().isEmpty();
    }
}
