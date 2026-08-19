package com.agentdrive.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 文件元数据、内容摘要、上传去重记录和回收站记录的 MyBatis 映射接口。
 *
 * <p>文件、去重和回收站数据均按 {@code userId} 限定所属 owner；查询方法返回数据库字段映射，
 * 写入和删除方法返回受影响的行数。
 */
@Mapper
public interface FileMapper {
    /**
     * 查询 owner 在指定路径上的文件记录。
     *
     * <p>SQL 按 {@code files.user_id = userId} 且 {@code files.path = path} 精确匹配，返回文件
     * ID、路径、目录标志、大小、revision、内容摘要以及创建和更新时间。
     *
     * @param userId 文件记录所属 owner 的 UUID 字符串
     * @param path 要精确查询的文件路径
     * @return 匹配的单条文件字段映射；不存在匹配记录时返回 {@code null}
     */
    Map<String, Object> selectByPath(@Param("userId") String userId,
                                     @Param("path") String path);

    /**
     * 查询文件当前 revision 对应的全文文档、chunk 和有效向量统计。
     *
     * @param userId 文件 owner UUID 字符串。
     * @param path 文件相对路径。
     * @param fingerprint 当前 embedding 配置指纹；为空时不把任何向量视为当前有效。
     * @return 当前索引状态字段；文件没有全文文档时仍返回一行聚合结果。
     */
    Map<String, Object> selectIndexStatus(@Param("userId") String userId,
                                          @Param("path") String path,
                                          @Param("fingerprint") String fingerprint);

    /**
     * 读取文件当前 revision 的抽取文档和文本块详情，不返回向量数值本身。
     * 查询同时返回每个 chunk 是否存有向量、是否匹配当前 fingerprint，供文件详情面板解释“已向量化”的具体含义。
     * @param userId 文件 owner UUID 字符串。
     * @param path 文件相对路径。
     * @param fingerprint 当前 embedding 配置指纹；为空时所有存量向量都不是当前有效向量。
     * @param limit 最多返回的数据库行数，调用方通常传入展示上限加一以检测截断。
     * @return 文档与 chunk 字段列表；没有当前文档时返回一行空文档聚合结果。
     */
    List<Map<String, Object>> selectIndexDetails(@Param("userId") String userId,
                                                 @Param("path") String path,
                                                 @Param("fingerprint") String fingerprint,
                                                 @Param("limit") int limit);

    /**
     * 查询 owner 下路径以指定前缀开头的文件记录。
     *
     * <p>SQL 使用 {@code path LIKE prefix || '%'}，因此匹配前缀本身及所有以此前缀开头的路径，
     * 并按目录优先、再按不区分大小写的路径排序。
     *
     * @param userId 文件记录所属 owner 的 UUID 字符串
     * @param prefix 路径匹配前缀
     * @return 按目录优先和路径排序的文件字段映射列表；没有匹配记录时返回空列表
     */
    List<Map<String, Object>> selectChildren(@Param("userId") String userId,
                                             @Param("prefix") String prefix);

    /**
     * 按 owner 和一组完整路径读取文件 metadata，供文件列表做一次性同步判断。
     *
     * @param userId 文件记录所属 owner 的 UUID 字符串
     * @param paths 要读取的完整 owner 相对路径
     * @return 已存在的 metadata 行；不存在的路径不返回
     */
    List<Map<String, Object>> selectByPaths(@Param("userId") String userId,
                                            @Param("paths") List<String> paths);

    /**
     * 插入或更新 owner 在指定路径上的文件元数据。
     *
     * <p>首次插入写入路径、目录标志和大小；同一 owner 与路径已存在时，仅更新目录标志、大小和
     * {@code updated_at}。冲突更新不会递增 {@code revision}，也不会修改内容摘要。
     *
     * @param userId 文件记录所属 owner 的 UUID 字符串
     * @param path 文件路径
     * @param isDir 是否为目录
     * @param size 文件或目录的字节大小
     * @return 插入或更新的文件记录数，成功时通常为 {@code 1}
     */
    int upsertMetadata(@Param("userId") String userId,
                       @Param("path") String path,
                       @Param("isDir") boolean isDir,
                       @Param("size") long size);

    /**
     * 一次性插入或更新多条 owner 文件 metadata，避免列表请求逐项往返数据库。
     *
     * @param userId 文件记录所属 owner 的 UUID 字符串
     * @param items 包含 {@code path}、{@code isDir} 和 {@code size} 的 metadata 项
     * @return 受影响的行数
     */
    int upsertMetadataBatch(@Param("userId") String userId,
                            @Param("items") List<Map<String, Object>> items);

    /**
     * 插入或更新 owner 在指定路径上的文件内容信息。
     *
     * <p>插入时强制记录为非目录并保存大小及 MD5/SHA-256 摘要；已有记录冲突时同样强制
     * {@code is_dir = false}，更新大小和摘要，并将 {@code revision} 加一，同时刷新
     * {@code updated_at}。
     *
     * @param userId 文件记录所属 owner 的 UUID 字符串
     * @param path 文件路径
     * @param size 文件字节大小
     * @param md5 文件内容的 MD5 摘要
     * @param sha256 文件内容的 SHA-256 摘要
     * @return 插入或更新的文件记录数，成功时通常为 {@code 1}
     */
    int upsertContent(@Param("userId") String userId,
                      @Param("path") String path,
                      @Param("size") long size,
                      @Param("md5") String md5,
                      @Param("sha256") String sha256);

    /**
     * 将 owner 指定路径当前 {@code files.revision} 的内容摘要写入修订历史。
     *
     * <p>SQL 先按 owner 和路径读取文件 ID 及当前 revision，再对 {@code file_revisions} 执行插入；
     * 同一文件和 revision 已存在时更新大小及摘要。路径没有对应文件时不会插入任何历史记录。
     *
     * @param userId 文件记录所属 owner 的 UUID 字符串
     * @param path 要记录修订历史的文件路径
     * @param size 该 revision 对应的文件字节大小
     * @param md5 该 revision 的 MD5 摘要
     * @param sha256 该 revision 的 SHA-256 摘要
     * @return 插入或更新的修订记录数；路径不存在时为 {@code 0}
     */
    int insertRevision(@Param("userId") String userId,
                       @Param("path") String path,
                       @Param("size") long size,
                       @Param("md5") String md5,
                       @Param("sha256") String sha256);

    /**
     * 删除 owner 在指定路径上的文件记录。
     *
     * <p>SQL 只按 {@code user_id} 和路径精确匹配删除，不会删除该 owner 的其他路径记录。
     *
     * @param userId 文件记录所属 owner 的 UUID 字符串
     * @param path 要删除的文件路径
     * @return 实际删除的文件记录数；没有匹配记录时为 {@code 0}
     */
    int deletePath(@Param("userId") String userId,
                   @Param("path") String path);

    /**
     * 删除 owner 在指定目录及其直接路径子树下的文件记录。
     *
     * <p>SQL 删除路径等于 {@code prefix} 的记录，以及以 {@code prefix + '/'} 开头的记录，
     * 因而只匹配完整目录边界，不会把同名前缀但不属于该目录的路径一并删除。
     *
     * @param userId 文件记录所属 owner 的 UUID 字符串
     * @param prefix 要删除的目录路径前缀
     * @return 实际删除的文件记录数；没有匹配记录时为 {@code 0}
     */
    int deletePrefix(@Param("userId") String userId,
                     @Param("prefix") String prefix);

    /**
     * 查询 owner 中已验证且 MD5 匹配的上传去重记录。
     *
     * <p>SQL 条件为 owner、{@code content_md5 = md5} 和 {@code verified = true}，返回摘要、路径、
     * 文件 revision、验证标志和更新时间。该查询本身不再检查路径对应文件当前的 revision。
     *
     * @param userId 去重记录所属 owner 的 UUID 字符串
     * @param md5 要匹配的文件内容 MD5 摘要
     * @return 匹配的去重字段映射；不存在已验证记录时返回 {@code null}
     */
    Map<String, Object> selectDedupe(@Param("userId") String userId,
                                     @Param("md5") String md5);

    /**
     * 插入或更新 owner 的 MD5 上传去重索引。
     *
     * <p>以 {@code (user_id, content_md5)} 为冲突键；冲突时替换路径、文件 revision、验证标志并
     * 更新 {@code updated_at}，用于记录该摘要当前对应的文件。
     *
     * @param userId 去重记录所属 owner 的 UUID 字符串
     * @param md5 文件内容 MD5 摘要
     * @param path 摘要对应的文件路径
     * @param revision 摘要对应的文件 revision
     * @param verified 是否允许该记录作为已验证去重命中
     * @return 插入或更新的去重记录数，成功时通常为 {@code 1}
     */
    int upsertDedupe(@Param("userId") String userId,
                     @Param("md5") String md5,
                     @Param("path") String path,
                     @Param("revision") long revision,
                     @Param("verified") boolean verified);

    /**
     * 删除 owner 中路径精确等于指定值的上传去重记录。
     *
     * @param userId 去重记录所属 owner 的 UUID 字符串
     * @param path 要清除索引的文件路径
     * @return 实际删除的去重记录数；没有匹配记录时为 {@code 0}
     */
    int deleteDedupeByPath(@Param("userId") String userId,
                           @Param("path") String path);

    /**
     * 为 owner 写入一条回收站记录。
     *
     * <p>SQL 保存回收站 ID、原始路径、回收站存储路径和删除时的文件 revision；该方法不做冲突
     * 更新，重复的 {@code trashId} 由数据库约束处理。
     *
     * @param trashId 回收站记录的 UUID 字符串
     * @param userId 回收站记录所属 owner 的 UUID 字符串
     * @param originalPath 文件被删除前的原始路径
     * @param storedPath 文件在回收站中的存储路径
     * @param revision 被删除文件对应的 revision
     * @return 插入的回收站记录数，成功时通常为 {@code 1}
     */
    int insertTrash(@Param("trashId") String trashId,
                    @Param("userId") String userId,
                    @Param("originalPath") String originalPath,
                    @Param("storedPath") String storedPath,
                    @Param("revision") long revision);

    /**
     * 查询 owner 的全部回收站记录。
     *
     * <p>返回回收站 ID、原始路径、存储路径、文件 revision、删除时间和过期时间，并按删除时间
     * 倒序排列。
     *
     * @param userId 回收站记录所属 owner 的 UUID 字符串
     * @return 按删除时间从新到旧排列的回收站字段映射列表；没有记录时返回空列表
     */
    List<Map<String, Object>> selectTrash(@Param("userId") String userId);

    /**
     * 查询 owner 中删除时间早于截止时间的回收站记录。
     *
     * <p>截止时间使用 Unix epoch 秒传入，避免 MyBatis 在不同 JDBC 驱动上对 Java
     * 时间类型的隐式转换差异；查询结果仍包含 revision，供存储层保护同路径新文件。</p>
     *
     * @param userId 回收站记录所属 owner 的 UUID 字符串。
     * @param cutoffEpoch 删除时间截止点的 Unix epoch 秒数。
     * @return 按删除时间从旧到新返回的过期回收站记录。
     */
    List<Map<String, Object>> selectExpiredTrash(@Param("userId") String userId,
                                                 @Param("cutoffEpoch") double cutoffEpoch);

    /**
     * 按回收站 ID 或原始路径查找 owner 的一条回收站记录。
     *
     * <p>SQL 条件匹配 {@code trash_id} 的文本值或 {@code original_path}，按删除时间倒序后只返回
     * 一条记录；同一路径存在多条记录时返回最近删除的记录。
     *
     * @param userId 回收站记录所属 owner 的 UUID 字符串
     * @param identifier 回收站 UUID 字符串或被删除文件的原始路径
     * @return 最近匹配的回收站字段映射；不存在匹配记录时返回 {@code null}
     */
    Map<String, Object> selectTrashByIdentifier(@Param("userId") String userId,
                                                @Param("identifier") String identifier);

    /**
     * 删除 owner 指定 ID 的回收站记录。
     *
     * @param userId 回收站记录所属 owner 的 UUID 字符串
     * @param trashId 要删除的回收站记录 UUID 字符串
     * @return 实际删除的回收站记录数；owner、ID 不匹配或记录不存在时为 {@code 0}
     */
    int deleteTrash(@Param("userId") String userId,
                    @Param("trashId") String trashId);

    /**
     * 删除 owner 的全部回收站记录。
     *
     * @param userId 回收站记录所属 owner 的 UUID 字符串
     * @return 实际删除的回收站记录数；该 owner 没有记录时为 {@code 0}
     */
    int deleteAllTrash(@Param("userId") String userId);
}
