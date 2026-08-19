package com.agentdrive.index;

import com.agentdrive.files.FileStorageException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 执行 owner-scoped 的文件语义搜索。
 *
 * <p>查询词先使用与文档相同的 Jina 模型生成 {@code retrieval.query} 向量，再交给
 * {@link IndexStore} 按当前 fingerprint 查询 pgvector。该服务只返回每个文件的最佳文本
 * chunk，不返回向量数值或 embedding API key。</p>
 */
public interface SemanticSearchService {
    /**
     * 在指定目录子树中查找与自然语言问题最相关的文件。
     *
     * @param ownerId 文件归属用户的 UUID
     * @param path 搜索根目录；空值表示 owner 根目录
     * @param query 自然语言搜索问题
     * @return 与普通文件列表兼容的语义搜索结果
     */
    Map<String, Object> search(UUID ownerId, String path, String query);

    /**
     * 基于当前 owner embedding 配置的 Jina 语义检索实现。
     */
    @Service
    @Profile({"java-files", "java-auth", "java-chat"})
    final class Jina implements SemanticSearchService {
        private static final int MAX_QUERY_LENGTH = 2000;
        private static final int MAX_RESULTS = 100;

        private final EmbeddingService embeddings;
        private final IndexStore index;

        /**
         * 创建语义搜索服务。
         *
         * @param embeddings 生成 retrieval.query 向量的 embedding 服务
         * @param index 执行 owner-scoped pgvector 查询的索引存储
         */
        public Jina(EmbeddingService embeddings, IndexStore index) {
            this.embeddings = embeddings;
            this.index = index;
        }

        /**
         * 校验查询、生成查询向量并把数据库行映射成文件列表条目。
         * Provider 或数据库失败时转换为稳定的文件 API 错误，不把底层异常文本返回给客户端。
         *
         * @param ownerId 文件归属用户的 UUID
         * @param path 已由文件存储层校验的目录路径
         * @param query 自然语言搜索问题
         * @return 搜索模式、问题和最佳 chunk 结果
         */
        @Override
        public Map<String, Object> search(UUID ownerId, String path, String query) {
            if (ownerId == null) throw new FileStorageException(401, "authentication required");
            String normalizedQuery = query == null ? "" : query.trim();
            if (normalizedQuery.isBlank()) {
                throw new FileStorageException(400, "语义搜索关键词不能为空");
            }
            if (normalizedQuery.length() > MAX_QUERY_LENGTH) {
                throw new FileStorageException(400, "语义搜索关键词过长");
            }

            EmbeddingService.QueryEmbedding queryEmbedding;
            try {
                queryEmbedding = embeddings.embedQuery(ownerId, normalizedQuery);
            } catch (IllegalStateException error) {
                if ("embedding_not_configured".equals(error.getMessage())) {
                    throw new FileStorageException(409, "请先配置 embedding 模型后再使用语义搜索");
                }
                throw new FileStorageException(502, "语义搜索向量服务暂时不可用");
            }

            List<Map<String, Object>> rows;
            try {
                rows = index.semanticSearch(ownerId, queryEmbedding.fingerprint(), queryEmbedding.vector(),
                        path == null || path.isBlank() ? null : path, MAX_RESULTS);
            } catch (RuntimeException error) {
                throw new FileStorageException(502, "语义搜索索引暂时不可用");
            }

            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String filePath = String.valueOf(row.getOrDefault("path", ""));
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", fileName(filePath));
                item.put("path", filePath);
                item.put("is_dir", false);
                item.put("size", row.getOrDefault("size", 0));
                item.put("mtime", row.get("mtime"));
                item.put("search_score", row.get("search_score"));
                item.put("search_snippet", row.get("search_snippet"));
                item.put("search_chunk_index", row.get("chunk_index"));
                items.add(item);
            }
            return Map.of(
                    "path", path == null ? "" : path,
                    "query", normalizedQuery,
                    "mode", "semantic",
                    "items", items
            );
        }

        /**
         * 从 owner 相对路径中取得展示用文件名。
         *
         * @param path 文件相对路径
         * @return 最后一个路径组件；空路径返回空字符串
         */
        private String fileName(String path) {
            int separator = path.lastIndexOf('/');
            return separator < 0 ? path : path.substring(separator + 1);
        }
    }
}
