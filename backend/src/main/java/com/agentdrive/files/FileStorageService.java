package com.agentdrive.files;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * 面向用户隔离的文件系统服务接口。
 * 路径参数均是用户存储根目录下的相对路径；实现负责拒绝越界、符号链接和内部 staging 路径，
 * 并在写入、移动、删除等可见变更后刷新元数据和索引通知。上传先写临时文件，只有发布方法成功后才进入用户目录。
 */
public interface FileStorageService extends FileContentPort {
    /**
     * 列出用户目录下指定目录的直接子项，并附带名称、类型、大小和修改时间等元数据。
     * 空路径表示用户根目录；目录外路径和内部目录应被拒绝。
     *
     * @param ownerId 文件归属用户的 UUID。
     * @param path 用户存储根目录下的相对目录路径。
     * @return 目录展示数据，通常包含规范化路径和子项列表。
     */
    Map<String, Object> list(UUID ownerId, String path);

    /**
     * 列出目录或目录子树中匹配查询词的可见条目。
     *
     * <p>默认实现保持旧存储适配器的行为；支持搜索的实现应在 owner 边界内匹配名称或
     * 相对路径，并继续执行与普通列表相同的内部路径和符号链接检查。</p>
     *
     * @param ownerId 文件归属用户的 UUID。
     * @param path 搜索根目录；空值表示 owner 根目录。
     * @param query 可选的名称/路径包含查询词。
     * @return 文件列表结构。
     */
    default Map<String, Object> list(UUID ownerId, String path, String query) {
        return list(ownerId, path);
    }

    /**
     * 按指定模式列出文件或执行语义搜索。
     *
     * <p>旧实现只实现名称/路径查询时可以继续使用默认实现；支持语义搜索的实现应在
     * owner 路径边界内执行向量检索，并返回与普通列表兼容的 {@code items} 结构。</p>
     *
     * @param ownerId 文件归属用户的 UUID。
     * @param path 普通模式的目录或语义模式的搜索根目录。
     * @param query 名称/路径关键词或语义搜索问题。
     * @param mode {@code name} 表示名称/路径搜索，{@code semantic} 表示向量语义搜索。
     * @return 文件列表、搜索模式和磁盘信息。
     * @throws FileStorageException mode 不是支持的搜索模式时抛出。
     */
    default Map<String, Object> list(UUID ownerId, String path, String query, String mode) {
        String normalizedMode = mode == null ? "name" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"name".equals(normalizedMode) && !"semantic".equals(normalizedMode)) {
            throw new FileStorageException(400, "不支持的文件搜索模式");
        }
        return list(ownerId, path, query);
    }

    /**
     * 分页/阈值版列表入口。旧存储适配器可继续使用默认实现；生产实现应返回
     * {@code limit}、{@code has_more}，语义模式还可应用 {@code minScore}。
     */
    default Map<String, Object> list(UUID ownerId, String path, String query, String mode,
                                     int limit, Double minScore) {
        return list(ownerId, path, query, mode);
    }

    /**
     * 带类型和修改时间边界的文件列表入口。
     * {@code type} 支持 all/file/folder/image/video/audio/pdf/text；时间使用 Unix 秒。
     */
    default Map<String, Object> list(UUID ownerId, String path, String query, String mode,
                                     int limit, Double minScore, String type,
                                     Double modifiedAfter, Double modifiedBefore) {
        return list(ownerId, path, query, mode, limit, minScore);
    }

    /**
     * 返回面向 Agent 回答的文件证据窗口。
     *
     * <p>与普通文件列表不同，该入口返回多个匹配 chunk 以及有限相邻 chunk，
     * 并携带 source revision、chunk index 和相关度。正文仍必须由调用方当作不可信
     * 文件数据处理。旧存储适配器默认不提供该能力。</p>
     *
     * @param ownerId 文件归属 owner
     * @param path 搜索根目录；空值表示 owner 根目录
     * @param query 自然语言搜索问题
     * @param limit 最多返回的匹配 chunk 数
     * @param neighbors 每个匹配 chunk 两侧带回的相邻 chunk 数
     * @param minScore 可选最低相关度
     * @param type 文件类型过滤
     * @param modifiedAfter 可选最早修改时间（Unix 秒）
     * @param modifiedBefore 可选最晚修改时间（Unix 秒）
     * @return 结构化证据结果
     */
    default Map<String, Object> searchContent(UUID ownerId, String path, String query,
                                               int limit, int neighbors, Double minScore,
                                               String type, Double modifiedAfter,
                                               Double modifiedBefore) {
        throw new UnsupportedOperationException("semantic evidence search is not supported");
    }

    /**
     * 在服务端递归统计当前 owner 目录下的可见文件、目录和字节数。
     * 统计不返回条目列表，避免 Agent 为了计数逐层发起请求；实现应跳过内部存储路径。
     *
     * @param ownerId 文件归属用户的 UUID。
     * @param path 统计根目录；空值表示 owner 根目录。
     * @return 包含 file_count、folder_count、total_size_bytes 和 snapshot_at 的完整统计；
     *         folder_count 不包含统计根目录本身。
     */
    default Map<String, Object> statistics(UUID ownerId, String path) {
        throw new UnsupportedOperationException("file statistics are not supported");
    }

    /**
     * 列出当前 owner 最近收藏的可见文件/目录。
     * 失效或已删除的收藏由实现过滤，不把孤儿路径返回给客户端。
     */
    default Map<String, Object> listFavorites(UUID ownerId, int limit) {
        throw new UnsupportedOperationException("favorite listing is not supported");
    }

    /**
     * 列出当前 owner 最近访问的可见文件。
     * 访问记录只作为排序线索，物理路径和 owner metadata 仍由实现重新校验。
     */
    default Map<String, Object> listRecent(UUID ownerId, int limit) {
        throw new UnsupportedOperationException("recent listing is not supported");
    }

    /** 列出当前 owner 指定文件的真实内容版本快照。 */
    default Map<String, Object> listVersions(UUID ownerId, String path, int limit) {
        throw new UnsupportedOperationException("file versions are not supported");
    }

    /** 将指定真实内容版本作为新 revision 原子恢复到当前文件路径。 */
    default Map<String, Object> restoreVersion(UUID ownerId, String path, String versionId) {
        throw new UnsupportedOperationException("file version restore is not supported");
    }

    /** 添加或移除 owner 对可见路径的收藏标记。 */
    default Map<String, Object> setFavorite(UUID ownerId, String path, boolean favorite) {
        throw new UnsupportedOperationException("favorites are not supported");
    }

    /**
     * 记录一次用户可见文件访问。该方法是 best-effort 观察记录，不能让预览或下载失败。
     */
    default void touchAccess(UUID ownerId, String path) {
        // Older storage test doubles do not persist access telemetry.
    }

    /**
     * 返回一个用户路径的详细元数据，包括文件或目录类型、大小、修改时间、revision 和可预览类型。
     * 该查询不会读取完整文件内容，也不会改变索引或文件 revision。
     *
     * @param ownerId 文件归属用户的 UUID。
     * @param path 用户存储根目录下的相对路径。
     * @return 目标路径的元数据；目标不存在时由实现抛出存储异常。
     */
    Map<String, Object> info(UUID ownerId, String path);

    /**
     * 读取文本文件的受限内容，用于“查看内容”而不是只显示预览片段。
     *
     * <p>默认实现用于兼容不需要内容读取的测试替身；生产文件服务会限制最大字节数、
     * 拒绝二进制文件并返回是否发生截断。</p>
     *
     * @param ownerId 文件归属用户的 UUID。
     * @param path 用户存储根目录下的相对文件路径。
     * @param maxBytes 本次最多读取的 UTF-8 字节数。
     * @return 文本内容和截断元数据。
     */
    default Map<String, Object> content(UUID ownerId, String path, int maxBytes) {
        throw new UnsupportedOperationException("text content is not supported");
    }

    /**
     * 按用户范围查询上传去重索引，并验证索引记录仍对应当前文件 revision。
     * 返回的命中结果只能用于免传优化，真正上传仍必须重新计算并校验 MD5。
     *
     * @param ownerId 文件归属用户的 UUID。
     * @param md5 客户端声明的文件 MD5 十六进制值。
     * @return 去重命中状态及已验证文件信息；无有效命中时返回未命中结果。
     */
    Map<String, Object> dedupe(UUID ownerId, String md5);

    /**
     * 解析一个可供内部读取或索引抽取的用户文件路径。
     * 实现必须在返回前检查相对路径、符号链接和内部目录边界；此方法只返回路径，不读取文件内容。
     *
     * @param ownerId 文件归属用户的 UUID。
     * @param path 用户存储根目录下的相对文件路径。
     * @return 经过安全校验的本地文件路径。
     * @throws FileStorageException 路径非法、目标不是文件或文件不存在时抛出。
     */
    Path fileForRead(UUID ownerId, String path);

    /**
     * 读取受控文件内容；默认适配器只依赖现有的安全路径端口，远程存储实现应覆盖此方法。
     * @param ownerId 文件归属 owner UUID
     * @param path owner-relative POSIX 路径
     * @param maxBytes 最大读取字节数
     * @return 文件原始字节
     */
    @Override
    default byte[] readBytes(UUID ownerId, String path, long maxBytes) {
        if (maxBytes <= 0 || maxBytes > Integer.MAX_VALUE) {
            throw new FileStorageException(400, "读取大小上限无效");
        }
        Path file = fileForRead(ownerId, path);
        try {
            long size = Files.size(file);
            if (size > maxBytes) throw new FileStorageException(413, "文件超过读取大小上限");
            return Files.readAllBytes(file);
        } catch (FileStorageException error) {
            throw error;
        } catch (IOException error) {
            throw new FileStorageException(500, "读取文件内容失败", error);
        }
    }

    /**
     * 在受保护的上传 staging 区创建一个权限受限的临时文件。
     * 调用方应把 multipart 数据流写入该文件，并在发布成功或请求失败后调用 {@link #discardTemp(Path)}。
     *
     * @return 新建的临时文件路径。
     * @throws FileStorageException 无法创建 staging 文件时抛出。
     */
    Path createUploadTemp();

    /**
     * 校验并原子发布一个已写入 staging 的上传文件。
     * 实现会重新计算 MD5、检查目标目录和文件名、按 {@code noclobber} 决定冲突处理，发布成功后刷新元数据并发出索引变更通知。
     * 临时文件不应在发布前被移动到用户可见目录。
     *
     * @param ownerId 文件归属用户的 UUID。
     * @param directory 目标目录的用户相对路径，空值表示根目录。
     * @param filename 用户提供的目标文件名，不包含目录分隔符。
     * @param tempFile 本次上传写入的 staging 文件路径。
     * @param declaredMd5 客户端声明的 MD5，服务端会与实际计算值比较。
     * @param noclobber 为 {@code true} 时遇到同名文件生成不覆盖的唯一名称；为 {@code false} 时允许原子覆盖。
     * @return 发布后的文件路径、revision、大小和实际 MD5 等结果。
     * @throws FileStorageException 校验失败、目标冲突、权限不足或发布 I/O 失败时抛出。
     */
    Map<String, Object> publishUpload(UUID ownerId,
                                       String directory,
                                       String filename,
                                       Path tempFile,
                                       String declaredMd5,
                                       boolean noclobber);

    /**
     * 以 UTF-8 编码原子写入一个文本文件；默认实现表示当前存储实现不支持该能力。
     * 支持该操作的实现应在替换可见文件后刷新 revision，并使旧全文和向量索引失效。
     *
     * @param ownerId 文件归属用户的 UUID。
     * @param path 要写入的用户相对路径。
     * @param content 要写入的完整文本内容。
     * @param overwrite 是否允许替换已存在文件。
     * @return 写入后的文件元数据。
     * @throws UnsupportedOperationException 当前实现不支持文本写入时抛出。
     * @throws FileStorageException 路径非法、类型冲突或写入失败时抛出。
     */
    default Map<String, Object> writeText(UUID ownerId, String path, String content, boolean overwrite) {
        throw new UnsupportedOperationException("text writes are not supported");
    }

    /**
     * 创建用户存储中的目录，并返回创建后的目录元数据。
     * 父目录可以按实现需要创建，但不能穿越用户根目录或创建内部保留目录。
     *
     * @param ownerId 目录归属用户的 UUID。
     * @param path 要创建的用户相对目录路径。
     * @return 新目录的元数据。
     * @throws FileStorageException 路径非法、已有文件阻挡或创建失败时抛出。
     */
    Map<String, Object> mkdir(UUID ownerId, String path);

    /**
     * 在同一用户存储根目录内原子重命名文件或目录。
     * 目标路径必须经过同样的安全校验；成功后源路径的索引和去重记录需要失效，目标路径需要重新登记。
     *
     * @param ownerId 源、目标路径所属用户的 UUID。
     * @param source 原用户相对路径。
     * @param destination 新用户相对路径。
     * @return 重命名后的目标元数据。
     * @throws FileStorageException 路径非法、源不存在、类型冲突或目标冲突时抛出。
     */
    Map<String, Object> rename(UUID ownerId, String source, String destination);

    /**
     * 把文件或目录移动到同一用户的目标目录，并按覆盖开关处理已存在目标。
     * 目录移动会使目录树下的索引和去重记录全部失效；文件系统发布点必须保持原子性。
     *
     * @param ownerId 源、目标路径所属用户的 UUID。
     * @param source 要移动的用户相对路径。
     * @param destinationDirectory 目标用户相对目录，而不是最终文件名。
     * @param overwrite 是否允许覆盖兼容类型的已有目标。
     * @return 移动后的目标元数据。
     * @throws FileStorageException 路径非法、源不存在、文件目录类型不兼容或移动失败时抛出。
     */
    Map<String, Object> move(UUID ownerId, String source, String destinationDirectory, boolean overwrite);

    /**
     * 复制文件或目录到同一用户存储中的目标路径。
     * 目录复制先在隐藏 staging 中完整构建并持久化，再发布到目标；覆盖事务保留可恢复的
     * marker/backup，避免目标目录只复制了一部分时对外可见；成功后目标树会产生新的索引变更。
     *
     * @param ownerId 源、目标路径所属用户的 UUID。
     * @param source 要复制的用户相对路径。
     * @param destination 目标用户相对路径。
     * @param overwrite 是否允许覆盖兼容类型的已有目标。
     * @return 复制后的目标元数据。
     * @throws FileStorageException 路径非法、源不存在、目标冲突或复制失败时抛出。
     */
    Map<String, Object> copy(UUID ownerId, String source, String destination, boolean overwrite);

    /**
     * 将用户路径移入带唯一 {@code trash_id} 的回收站，而不是立即永久删除。
     * 该操作应记录原路径和删除时间，并使原路径的全文、向量及去重索引失效。
     *
     * @param ownerId 文件归属用户的 UUID。
     * @param path 要删除的用户相对路径。
     * @return 回收站条目标识、原路径及删除结果。
     * @throws FileStorageException 路径非法、目标不存在或移入回收站失败时抛出。
     */
    Map<String, Object> deleteToTrash(UUID ownerId, String path);

    /**
     * 列出用户回收站中的有效条目。
     * 没有对应元数据的孤儿条目不应作为可恢复文件展示；返回的 {@code trash_id} 是恢复操作的首选标识。
     *
     * @param ownerId 回收站归属用户的 UUID。
     * @return 回收站条目及其原路径、删除时间和恢复标识。
     */
    Map<String, Object> listTrash(UUID ownerId);

    /**
     * 按回收站标识恢复条目；为兼容旧客户端也可接受旧格式原路径。
     * 恢复会检查原目标是否仍可用，成功后从回收站移回用户目录并重新发布元数据；目标冲突不会静默覆盖。
     *
     * @param ownerId 回收站归属用户的 UUID。
     * @param trashIdOrPath 新客户端传入的 {@code trash_id}，或旧客户端传入的原路径。
     * @return 恢复后的文件或目录元数据。
     * @throws FileStorageException 标识不存在、目标冲突、路径非法或恢复失败时抛出。
     */
    Map<String, Object> restoreTrash(UUID ownerId, String trashIdOrPath);

    /**
     * 永久删除该用户回收站中所有可清理条目及其元数据。
     * 清理应在存储锁保护下执行，并返回实际删除数量；不存在或不完整的孤儿元数据可由实现按清理策略移除。
     *
     * @param ownerId 回收站归属用户的 UUID。
     * @return 永久删除结果及删除数量。
     * @throws FileStorageException 回收站清理失败时抛出。
     */
    Map<String, Object> emptyTrash(UUID ownerId);

    /**
     * 删除指定保留期之前的回收站条目，并保留仍在保留期内的内容。
     *
     * <p>该接口供显式维护命令使用，和 {@link #emptyTrash(UUID)} 的语义不同：
     * 它只处理 {@code deleted_at} 早于截止时间的条目，并继续按 revision 检查保护
     * 新建同路径文件。</p>
     *
     * @param ownerId 回收站归属用户的 UUID。
     * @param retentionDays 回收站保留天数，服务实现会将非法或过大的值限制到合理范围。
     * @return 实际移除条目数量及采用的保留天数。
     * @throws FileStorageException 回收站清理失败时抛出。
     */
    default Map<String, Object> cleanupTrash(UUID ownerId, int retentionDays) {
        throw new UnsupportedOperationException("expired trash cleanup is not supported");
    }

    /**
     * 删除上传失败或发布完成后遗留的 staging 临时文件。
     * 只允许处理服务创建的临时路径；对用户可见文件的路径应拒绝，避免清理接口成为删除任意文件的入口。
     *
     * @param tempFile {@link #createUploadTemp()} 返回的临时文件路径。
     * @throws FileStorageException 临时路径不属于 staging 区或删除失败时抛出。
     */
    void discardTemp(Path tempFile);
}
