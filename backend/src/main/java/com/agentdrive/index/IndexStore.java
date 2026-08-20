package com.agentdrive.index;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 保存用户文件全文、文本块和向量元数据的索引持久化接口。
 * 全部查询和更新都按用户隔离；全文记录必须带 source revision/抽取器版本，向量记录必须带 embedding fingerprint，
 * 这样文件变更或模型切换后旧向量会被视为无效，而不会混入新的检索结果。
 */
public interface IndexStore {
    /**
     * 当前 owner 文件索引的全盘统计。
     *
     * @param eligibleFiles 可参与全文索引的普通文件数。
     * @param extractedFiles 已有当前 revision 全文文档的文件数。
     * @param vectorFiles 至少拥有完整当前 fingerprint 向量的文件数。
     * @param nonVectorizableFiles 当前文档没有可切分文本块的文件数。
     * @param missingVectors 仍缺少当前 fingerprint 向量的文件数。
     * @param staleVectors 仍有旧 fingerprint 向量的文件数。
     */
    record Stats(int eligibleFiles, int extractedFiles, int vectorFiles,
                 int nonVectorizableFiles, int missingVectors, int staleVectors) {
        /**
         * 转为任务概览 API 使用的 snake_case 字段。
         * @return 不包含敏感信息的统计映射。
         */
        public Map<String, Object> asMap() {
            return Map.of(
                    "eligible_files", eligibleFiles,
                    "extracted_files", extractedFiles,
                    "vector_files", vectorFiles,
                    "non_vectorizable_files", nonVectorizableFiles,
                    "missing_vectors", missingVectors,
                    "stale_vectors", staleVectors
            );
        }
    }

    /**
     * 全盘读取 owner 当前 revision 的索引和向量状态。
     * 该查询只用于任务总览，调用方负责短时间缓存，不能放入文件列表或上传请求路径。
     *
     * @param userId 文件归属 owner 的 UUID。
     * @param fingerprint 当前 embedding 配置指纹；为空时不把任何已存向量计为有效。
     * @return owner 级索引统计。
     */
    Stats statistics(UUID userId, String fingerprint);

    /**
     * 查找用户某个文件在索引中的元数据，包括文件 ID、revision、大小和索引状态。
     *
     * @param userId 文件归属用户的 UUID。
     * @param path 用户相对文件路径。
     * @return 索引中的文件元数据；没有对应文件时返回 {@code null}。
     */
    Map<String, Object> file(UUID userId, String path);

    /**
     * 列出用户文件索引中匹配路径前缀的文件元数据，供全文重建逐个处理。
     *
     * @param userId 文件归属用户的 UUID。
     * @param prefix 可选的用户相对路径前缀；空值表示不限制前缀。
     * @return 按存储顺序返回的文件元数据列表。
     */
    List<Map<String, Object>> files(UUID userId, String prefix);

    /**
     * 使用当前 embedding fingerprint 在 owner 范围内执行语义检索。
     * 每个文件只返回距离最近的一个 chunk，调用方可以用该 chunk 作为匹配片段展示。
     *
     * @param userId 文件归属用户的 UUID。
     * @param fingerprint 当前 embedding 模型配置指纹。
     * @param vector 查询词向量的 PostgreSQL 数组文字。
     * @param prefix 可选的目录路径前缀；为空时搜索 owner 全部文件。
     * @param limit 最多返回的文件数。
     * @return 按相似度降序排列的文件和最佳匹配 chunk。
     */
    List<Map<String, Object>> semanticSearch(UUID userId, String fingerprint, String vector,
                                              String prefix, int limit);

    /**
     * 用一次抽取结果替换文件的全文记录和全部文本块。
     * 实现应以 {@code sourceRevision} 和版本字段记录这次抽取的来源，并使该文件旧向量失效；调用方不应在这里调用外部 embedding 服务。
     *
     * @param userId 文件归属用户的 UUID。
     * @param fileId 文件在索引表中的 UUID。
     * @param sourceRevision 抽取时文件的 revision，用于拒绝过期全文或向量。
     * @param content 完整抽取正文。
     * @param extractorVersion 产生正文的抽取器版本。
     * @param chunks 按固定窗口切好的正文块，顺序决定 chunk 序号。
     * @param chunkVersion 切分算法版本。
     */
    void replaceDocument(UUID userId, UUID fileId, long sourceRevision, String content,
                         String extractorVersion, List<String> chunks, String chunkVersion);

    /**
     * 删除索引中已不再对应用户现有文件的文档和 chunks。
     *
     * @param userId 要清理索引的用户 UUID。
     * @return 被删除的索引文件记录数量。
     */
    int cleanup(UUID userId);

    /**
     * 选取仍缺少向量或向量 fingerprint 与当前配置不一致的 chunks。
     * 返回结果包含 chunk ID 和正文，最多返回 {@code limit} 条；成功写回后下一轮查询应自然跳过这些 chunks。
     *
     * @param userId 文件归属用户的 UUID。
     * @param fingerprint 当前 provider/base URL/model 的指纹。
     * @param limit 本次最多返回的 chunk 数量。
     * @return 待向量化的 chunk 记录列表。
     */
    List<Map<String, Object>> chunks(UUID userId, String fingerprint, int limit);

    /**
     * 在指定文件路径集合内选取缺少当前 fingerprint 向量的 chunks。
     * 路径过滤和 source revision 校验必须在持久化查询中完成，避免同名路径或过期全文被错误向量化。
     *
     * @param userId 文件归属用户的 UUID。
     * @param fingerprint 当前 provider/base URL/model 的指纹。
     * @param paths 要处理的用户相对文件路径列表。
     * @param limit 本次最多返回的 chunk 数量。
     * @return 指定文件中待向量化的 chunk 记录列表。
     */
    List<Map<String, Object>> chunks(UUID userId, String fingerprint, List<String> paths, int limit);

    /**
     * 清除指定文件的已有向量，使其在后续 embedding 任务中重新生成。
     * 空路径列表表示不限定文件范围；实现应仍按用户隔离，并返回实际清除的 chunk 数。
     *
     * @param userId 文件归属用户的 UUID。
     * @param paths 要清除向量的用户相对路径列表。
     * @return 被清除向量的 chunk 数量。
     */
    int clearEmbeddings(UUID userId, List<String> paths);

    /**
     * 把 provider 返回的向量写回一个 chunk，并记录当前 fingerprint。
     * 更新必须再次按用户、chunk ID 和 source revision 约束目标；目标已变化或不存在时返回 0，避免迟到的 provider 响应污染新内容。
     *
     * @param userId 文件归属用户的 UUID。
     * @param chunkId 要更新的 chunk UUID。
     * @param vector 已校验的向量文字表示。
     * @param fingerprint 生成该向量的 provider 配置指纹。
     * @return 实际更新的记录数，通常为 0 或 1。
     */
    int updateEmbedding(UUID userId, UUID chunkId, String vector, String fingerprint);
}
