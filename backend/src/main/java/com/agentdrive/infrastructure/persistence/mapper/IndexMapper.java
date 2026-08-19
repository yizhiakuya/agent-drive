package com.agentdrive.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 文件全文文档、文本分块和向量索引的 MyBatis 映射接口。
 *
 * <p>文件查询、文档清理和向量更新均通过 owner 或当前文件 revision 限定数据，避免为旧 revision
 * 写入或读取索引；删除和更新方法返回受影响的行数，文档 upsert 返回数据库生成的文档 ID。
 */
@Mapper
public interface IndexMapper {
    /**
     * 查询 owner 指定路径对应的非目录文件。
     *
     * <p>SQL 按 owner、路径精确匹配，并要求 {@code is_dir = false}；返回文件 ID、路径、大小、
     * revision 和内容摘要。
     *
     * @param userId 文件所属 owner 的 UUID 字符串
     * @param path 要精确查询的文件路径
     * @return 匹配的文件字段映射；不存在文件、路径属于目录或 owner 不匹配时返回 {@code null}
     */
    Map<String, Object> selectFile(@Param("userId") String userId, @Param("path") String path);

    /**
     * 查询 owner 的非目录文件，可选地限制在指定目录路径下。
     *
     * <p>{@code prefix} 为 {@code null} 时返回 owner 的全部非目录文件；否则只匹配路径等于前缀
     * 或以 {@code prefix + '/'} 开头的文件。结果按路径升序排列。
     *
     * @param userId 文件所属 owner 的 UUID 字符串
     * @param prefix 可为空的目录路径前缀
     * @return 按路径升序排列的文件字段映射列表；没有匹配记录时返回空列表
     */
    List<Map<String, Object>> selectFiles(@Param("userId") String userId, @Param("prefix") String prefix);

    /**
     * 在 owner 当前 revision 的有效向量中按 cosine distance 检索文件。
     *
     * <p>SQL 会按文件分组，只保留最佳 chunk；查询向量只作为绑定参数转换为 pgvector，
     * 不把原始查询拼接进 SQL。结果同时返回可展示的匹配片段和相关度。</p>
     *
     * @param userId 文件所属 owner 的 UUID 字符串
     * @param fingerprint 当前 embedding 配置指纹
     * @param vector 查询向量的 PostgreSQL 数组文字
     * @param prefix 可选的目录路径前缀
     * @param limit 返回文件数量上限
     * @return 按相关度排序的文件匹配行
     */
    List<Map<String, Object>> semanticSearch(@Param("userId") String userId,
                                             @Param("fingerprint") String fingerprint,
                                             @Param("vector") String vector,
                                             @Param("prefix") String prefix,
                                             @Param("limit") int limit);

    /**
     * 保存 owner 指定文件 revision 的抽取文档内容。
     *
     * <p>SQL 只在 {@code fileId} 对应文件属于 {@code userId} 时插入文档；以文件、source revision
     * 和抽取器版本为冲突键，冲突时更新正文及 {@code updated_at}。语句通过 {@code RETURNING}
     * 返回文档 ID，不会返回正文。
     *
     * @param userId 文件所属 owner 的 UUID 字符串
     * @param fileId 文件记录的 UUID 字符串
     * @param sourceRevision 文档对应的文件 revision
     * @param content 抽取出的全文内容
     * @param extractorVersion 生成正文所用的抽取器版本
     * @return 含 {@code id} 字段的文档映射；文件不存在或不属于该 owner 时返回 {@code null}
     */
    Map<String, Object> upsertDocument(@Param("userId") String userId,
                                       @Param("fileId") String fileId,
                                       @Param("sourceRevision") long sourceRevision,
                                       @Param("content") String content,
                                       @Param("extractorVersion") String extractorVersion);

    /**
     * 删除指定文档的全部文本分块。
     *
     * <p>SQL 仅按 {@code document_id} 匹配，没有额外的 owner 条件；调用方必须确保文档 ID 已由
     * 正确 owner 的索引流程取得。
     *
     * @param documentId 要删除分块的文档 UUID 字符串
     * @return 实际删除的分块数；文档没有分块时为 {@code 0}
     */
    int deleteChunks(@Param("documentId") String documentId);

    /**
     * 删除 owner 指定文件不属于当前抽取版本的文档记录。
     *
     * <p>SQL 先要求文件 ID 属于 owner，再删除该文件下 {@code source_revision} 或
     * {@code extractor_version} 与传入值不同的文档；当前 revision 和当前抽取器版本的文档会保留。
     *
     * @param userId 文件所属 owner 的 UUID 字符串
     * @param fileId 文件记录的 UUID 字符串
     * @param sourceRevision 当前有效的文件 revision
     * @param extractorVersion 当前使用的抽取器版本
     * @return 实际删除的旧文档数；没有过期文档或文件不属于该 owner 时为 {@code 0}
     */
    int deleteOtherDocuments(@Param("userId") String userId, @Param("fileId") String fileId,
                             @Param("sourceRevision") long sourceRevision,
                             @Param("extractorVersion") String extractorVersion);

    /**
     * 插入或更新文档的一个文本分块。
     *
     * <p>以 {@code (document_id, chunk_index, chunk_version)} 为冲突键；冲突时更新 source revision
     * 和正文，并清空已有向量及向量指纹，使分块内容变化后必须重新嵌入。SQL 不通过 owner 过滤，
     * 文档归属由调用方保证。
     *
     * @param documentId 文档 UUID 字符串
     * @param chunkIndex 分块在文档中的序号
     * @param sourceRevision 分块对应的文件 revision
     * @param chunkVersion 分块生成规则的版本
     * @param content 分块正文
     * @return 插入或更新的分块记录数，成功时通常为 {@code 1}
     */
    int insertChunk(@Param("documentId") String documentId,
                    @Param("chunkIndex") int chunkIndex,
                    @Param("sourceRevision") long sourceRevision,
                    @Param("chunkVersion") String chunkVersion,
                    @Param("content") String content);

    /**
     * 清理 owner 文件 revision 已过期的文档记录。
     *
     * <p>SQL 将 {@code documents} 与 owner 的 {@code files} 连接，仅删除
     * {@code document.source_revision <> file.revision} 的文档，保留与当前文件 revision 一致的文档。
     *
     * @param userId 文件所属 owner 的 UUID 字符串
     * @return 实际删除的过期文档数；没有过期文档时为 {@code 0}
     */
    int cleanup(@Param("userId") String userId);

    /**
     * 分页查询 owner 当前文件 revision 下需要生成或更新向量的文本分块。
     *
     * <p>SQL 要求文档 revision 等于文件 revision，分块 revision 等于文档 revision；{@code paths}
     * 非空时再限制文件路径集合，{@code fingerprint} 非空时排除已有相同指纹的分块。结果按分块
     * ID 升序并受 {@code limit} 限制，返回分块正文、版本、文档 ID 和文件路径。
     *
     * @param userId 文件所属 owner 的 UUID 字符串
     * @param fingerprint 当前嵌入模型的指纹；为 {@code null} 时不按指纹过滤
     * @param paths 可选的文件路径集合；为空或 {@code null} 时不限制路径
     * @param limit 本次最多返回的分块数
     * @return 按分块 ID 升序排列的待处理分块字段映射列表；没有匹配记录时返回空列表
     */
    List<Map<String, Object>> selectChunks(@Param("userId") String userId,
                                           @Param("fingerprint") String fingerprint,
                                           @Param("paths") List<String> paths,
                                           @Param("limit") int limit);

    /**
     * 清空 owner 当前文件 revision 下指定文件的向量及向量指纹。
     *
     * <p>只更新文档和分块 revision 均与当前文件 revision 一致的分块；{@code paths} 非空时仅
     * 更新这些路径，空集合或 {@code null} 表示 owner 下全部路径。
     *
     * @param userId 文件所属 owner 的 UUID 字符串
     * @param paths 可选的文件路径集合；为空或 {@code null} 时不限制路径
     * @return 实际清空向量的分块数；没有匹配分块时为 {@code 0}
     */
    int clearEmbeddings(@Param("userId") String userId, @Param("paths") List<String> paths);

    /**
     * 为 owner 当前 revision 下的指定文本分块写入向量及模型指纹。
     *
     * <p>SQL 同时校验分块 ID、文档归属、owner 以及文档和分块均与文件当前 revision 一致；
     * 任一条件不满足都不会更新记录。
     *
     * @param userId 文件所属 owner 的 UUID 字符串
     * @param chunkId 文本分块 UUID 字符串
     * @param vector 向量文本，将转换为 PostgreSQL {@code vector} 类型
     * @param fingerprint 生成该向量的嵌入模型指纹
     * @return 实际更新的分块数；分块不存在、owner 不匹配或 revision 已过期时为 {@code 0}
     */
    int updateEmbedding(@Param("userId") String userId, @Param("chunkId") String chunkId,
                        @Param("vector") String vector, @Param("fingerprint") String fingerprint);
}
