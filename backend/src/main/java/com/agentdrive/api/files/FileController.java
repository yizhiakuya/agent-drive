package com.agentdrive.api.files;

import com.agentdrive.api.ReactiveExecution;
import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.files.FileStorageService;
import com.agentdrive.auth.AuthenticatedPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 暴露 owner-scoped 文件列表、读写、回收站和上传端点。
 *
 * <p>文件路径和存储安全由 {@link FileStorageService} 统一校验；控制器只负责解析
 * HTTP 参数、解析用户主体并把同步文件系统操作移到 bounded-elastic。上传先把
 * multipart 内容写入临时文件，再由存储服务校验 MD5 并执行原子发布；索引由显式业务 operation 执行，
 * 最终无论成功或失败都会清理临时文件。
 */
@RestController
@Profile({"java-files", "java-auth", "java-chat"})
@RequestMapping("/api/v1/files")
public final class FileController {
    private static final Set<String> INLINE_MEDIA_TYPES = Set.of(
            "application/pdf",
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp",
            "audio/mpeg", "audio/wav", "audio/mp4", "audio/flac", "audio/ogg",
            "video/mp4", "video/webm", "video/ogg", "video/quicktime"
    );
    private final FileStorageService files;
    private final WebRequestPrincipalResolver principalResolver;

    /**
     * 创建文件 API 控制器。
     *
     * @param files 执行 owner-scoped 路径解析、文件变更、上传发布和回收站操作的存储服务。
     * @param principalResolver 解析 Cookie、Bearer 或媒体查询令牌中的用户主体。
     */
    public FileController(FileStorageService files, WebRequestPrincipalResolver principalResolver) {
        this.files = files;
        this.principalResolver = principalResolver;
    }

    /**
     * 响应 {@code GET /api/v1/files}，列出指定目录下当前用户可见的文件。
     *
     * @param path owner 根目录下的相对目录路径，默认表示根目录。
     * @param q 名称/路径关键词或语义搜索问题。
     * @param mode {@code name} 表示名称/路径搜索，{@code semantic} 表示向量语义搜索。
     * @param exchange 用于解析文件所有者的请求上下文。
     * @return 文件条目及目录信息的异步 JSON 响应。
     */
    @GetMapping
    public Mono<Map<String, Object>> list(@RequestParam(defaultValue = "") String path,
                                          @RequestParam(defaultValue = "") String q,
                                          @RequestParam(defaultValue = "name") String mode,
                                          @RequestParam(defaultValue = "1000") int limit,
                                          @RequestParam(name = "min_score", required = false) Double minScore,
                                          @RequestParam(defaultValue = "all") String type,
                                          @RequestParam(name = "modified_after", required = false) Double modifiedAfter,
                                          @RequestParam(name = "modified_before", required = false) Double modifiedBefore,
                                          ServerWebExchange exchange) {
        if (limit < 1 || limit > 1000) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "limit must be between 1 and 1000");
        }
        if (minScore != null && (!Double.isFinite(minScore) || minScore < -1.0 || minScore > 1.0)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "min_score must be between -1 and 1");
        }
        String normalizedType = type == null ? "all" : type.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("all", "file", "folder", "image", "video", "audio", "pdf", "text").contains(normalizedType)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "unsupported file type filter");
        }
        if (modifiedAfter != null && (!Double.isFinite(modifiedAfter) || modifiedAfter < 0)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "modified_after must be a non-negative timestamp");
        }
        if (modifiedBefore != null && (!Double.isFinite(modifiedBefore) || modifiedBefore < 0)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "modified_before must be a non-negative timestamp");
        }
        if (modifiedAfter != null && modifiedBefore != null && modifiedAfter > modifiedBefore) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "modified_after must not exceed modified_before");
        }
        return authenticated(exchange, owner -> files.list(owner, path, q, mode, limit, minScore,
                normalizedType, modifiedAfter, modifiedBefore));
    }

    /**
     * 响应 {@code GET /api/v1/files/stats}，在服务端递归统计当前 owner 的可见文件。
     *
     * @param path owner 根目录下的相对统计目录，默认表示根目录。
     * @param exchange 用于解析文件所有者的请求上下文。
     * @return 文件数、目录数、字节数和统计快照时间。
     */
    @GetMapping("/stats")
    public Mono<Map<String, Object>> statistics(@RequestParam(defaultValue = "") String path,
                                                ServerWebExchange exchange) {
        return authenticated(exchange, owner -> files.statistics(owner, path));
    }

    /**
     * 响应 {@code GET /api/v1/files/dedupe}，查询当前用户是否已有经服务端验证的 MD5 文件。
     *
     * @param md5 待查询的十六进制 MD5；服务层会按当前文件 revision 验证索引命中。
     * @param exchange 用于确定查询 owner 的请求上下文。
     * @return 去重命中信息，未命中时不会修改文件或索引。
     */
    @GetMapping("/dedupe")
    public Mono<Map<String, Object>> dedupe(@RequestParam String md5, ServerWebExchange exchange) {
        return authenticated(exchange, owner -> files.dedupe(owner, md5));
    }

    /**
     * 响应 {@code GET /api/v1/files/info}，返回单个文件或目录的元数据。
     *
     * @param path owner 根目录下的相对路径。
     * @param exchange 用于确定文件 owner 的请求上下文。
     * @return 存储服务生成的文件元数据。
     */
    @GetMapping("/info")
    public Mono<Map<String, Object>> info(@RequestParam String path, ServerWebExchange exchange) {
        return authenticated(exchange, owner -> files.info(owner, path));
    }

    /**
     * 响应 {@code GET /api/v1/files/content}，读取文本文件的受限完整内容。
     *
     * @param path owner 根目录下的相对文本文件路径。
     * @param maxBytes 最多读取的 UTF-8 字节数，服务层还会应用硬上限。
     * @param exchange 用于确定文件 owner 的请求上下文。
     * @return 文本内容、编码、原始大小和截断标志。
     */
    @GetMapping("/content")
    public Mono<Map<String, Object>> content(@RequestParam String path,
                                              @RequestParam(name = "max_bytes", defaultValue = "1048576") int maxBytes,
                                              ServerWebExchange exchange) {
        return authenticated(exchange, owner -> files.content(owner, path, maxBytes));
    }

    /**
     * 响应 {@code GET /api/v1/files/trash}，列出当前用户回收站条目。
     *
     * @param exchange 用于限定回收站 owner 的请求上下文。
     * @return 回收站元数据列表。
     */
    @GetMapping("/trash")
    public Mono<Map<String, Object>> trash(ServerWebExchange exchange) {
        return authenticated(exchange, files::listTrash);
    }

    /** 列出当前 owner 最近收藏的可见文件和目录。 */
    @GetMapping("/favorites")
    public Mono<Map<String, Object>> favorites(@RequestParam(defaultValue = "100") int limit,
                                               ServerWebExchange exchange) {
        validateTrackingLimit(limit);
        return authenticated(exchange, owner -> files.listFavorites(owner, limit));
    }

    /** 添加当前 owner 的文件/目录收藏标记。 */
    @PostMapping("/favorites")
    public Mono<Map<String, Object>> addFavorite(@RequestParam String path,
                                                 ServerWebExchange exchange) {
        return authenticated(exchange, owner -> files.setFavorite(owner, path, true));
    }

    /** 删除当前 owner 的文件/目录收藏标记。 */
    @DeleteMapping("/favorites")
    public Mono<Map<String, Object>> removeFavorite(@RequestParam String path,
                                                    ServerWebExchange exchange) {
        return authenticated(exchange, owner -> files.setFavorite(owner, path, false));
    }

    /** 列出当前 owner 最近访问且仍然存在的普通文件。 */
    @GetMapping("/recent")
    public Mono<Map<String, Object>> recent(@RequestParam(defaultValue = "100") int limit,
                                            ServerWebExchange exchange) {
        validateTrackingLimit(limit);
        return authenticated(exchange, owner -> files.listRecent(owner, limit));
    }

    /** 列出当前 owner 文件的真实内容版本快照。 */
    @GetMapping("/versions")
    public Mono<Map<String, Object>> versions(@RequestParam String path,
                                              @RequestParam(defaultValue = "20") int limit,
                                              ServerWebExchange exchange) {
        if (limit < 1 || limit > 50) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "limit must be between 1 and 50");
        }
        return authenticated(exchange, owner -> files.listVersions(owner, path, limit));
    }

    /** 将指定真实内容版本作为新 revision 恢复到原路径。 */
    @PostMapping("/versions/restore")
    public Mono<Map<String, Object>> restoreVersion(@RequestParam String path,
                                                    @RequestParam String version_id,
                                                    ServerWebExchange exchange) {
        return authenticated(exchange, owner -> files.restoreVersion(owner, path, version_id));
    }

    /**
     * 响应 {@code GET /api/v1/files/raw}，以内联资源形式读取文件。
     *
     * @param path 待读取的 owner 相对文件路径。
     * @param exchange 用于解析媒体请求凭据的上下文。
     * @return 带探测媒体类型的文件资源响应，不设置附件下载处置。
     */
    @GetMapping("/raw")
    public Mono<ResponseEntity<Resource>> raw(@RequestParam String path, ServerWebExchange exchange) {
        return media(exchange, path, false);
    }

    /**
     * 响应 {@code GET /api/v1/files/download}，以附件形式读取文件。
     *
     * @param path 待读取的 owner 相对文件路径。
     * @param exchange 用于解析媒体请求凭据的上下文。
     * @return 带媒体类型和 {@code attachment} Content-Disposition 的文件资源响应。
     */
    @GetMapping("/download")
    public Mono<ResponseEntity<Resource>> download(@RequestParam String path, ServerWebExchange exchange) {
        return media(exchange, path, true);
    }

    private void validateTrackingLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "limit must be between 1 and 100");
        }
    }

    /**
     * 响应 {@code POST /api/v1/files/mkdir}，在当前用户空间创建目录。
     *
     * @param path 要创建的 owner 相对目录路径。
     * @param exchange 用于确定目录 owner 的请求上下文。
     * @return 存储服务返回的创建结果；路径安全和已存在冲突由存储服务处理。
     */
    @PostMapping("/mkdir")
    public Mono<Map<String, Object>> mkdir(@RequestParam String path, ServerWebExchange exchange) {
        return authenticated(exchange, owner -> files.mkdir(owner, path));
    }

    /**
     * 响应 {@code POST /api/v1/files/rename}，在同一父目录下改名。
     *
     * @param src 当前用户空间中的原相对路径。
     * @param dst 改名后的目标相对路径。
     * @param exchange 用于限定变更 owner 的请求上下文。
     * @return 存储服务返回的改名结果，并触发相关索引失效/更新。
     */
    @PostMapping("/rename")
    public Mono<Map<String, Object>> rename(@RequestParam String src,
                                            @RequestParam String dst,
                                            ServerWebExchange exchange) {
        return authenticated(exchange, owner -> files.rename(owner, src, dst));
    }

    /**
     * 响应 {@code POST /api/v1/files/move}，将文件或目录移动到目标目录。
     *
     * @param src 当前用户空间中的源相对路径。
     * @param dst_dir 目标目录相对路径，而非带新文件名的完整路径。
     * @param overwrite 是否允许按存储层规则覆盖同名目标。
     * @param exchange 用于限定变更 owner 的请求上下文。
     * @return 存储服务返回的移动结果，并处理旧路径索引失效。
     */
    @PostMapping("/move")
    public Mono<Map<String, Object>> move(@RequestParam String src,
                                          @RequestParam String dst_dir,
                                          @RequestParam(defaultValue = "false") boolean overwrite,
                                          ServerWebExchange exchange) {
        return authenticated(exchange, owner -> files.move(owner, src, dst_dir, overwrite));
    }

    /**
     * 响应 {@code POST /api/v1/files/copy}，复制文件或目录到目标路径。
     *
     * @param src 当前用户空间中的源相对路径。
     * @param dst 目标相对路径。
     * @param overwrite 是否允许按存储层规则覆盖目标。
     * @param exchange 用于限定复制 owner 的请求上下文。
     * @return 存储服务返回的复制结果；复制完成后异步索引新内容。
     */
    @PostMapping("/copy")
    public Mono<Map<String, Object>> copy(@RequestParam String src,
                                          @RequestParam String dst,
                                          @RequestParam(defaultValue = "false") boolean overwrite,
                                          ServerWebExchange exchange) {
        return authenticated(exchange, owner -> files.copy(owner, src, dst, overwrite));
    }

    /**
     * 响应 {@code POST /api/v1/files/delete}，把文件或目录移入当前用户回收站。
     *
     * @param path 待删除的 owner 相对路径。
     * @param exchange 用于限定删除 owner 的请求上下文。
     * @return 回收站删除结果；原路径索引会先失效。
     */
    @PostMapping("/delete")
    public Mono<Map<String, Object>> delete(@RequestParam String path, ServerWebExchange exchange) {
        return authenticated(exchange, owner -> files.deleteToTrash(owner, path));
    }

    /**
     * 响应 {@code POST /api/v1/files/trash/restore}，恢复回收站条目。
     *
     * @param trash_id 新契约中的唯一回收站 ID；存在时优先使用它。
     * @param path 旧客户端兼容的原路径参数，在 {@code trash_id} 缺失时使用。
     * @param exchange 用于限定恢复 owner 的请求上下文。
     * @return 存储服务返回的恢复结果。
     */
    @PostMapping("/trash/restore")
    public Mono<Map<String, Object>> restore(@RequestParam(required = false) String trash_id,
                                             @RequestParam(required = false) String path,
                                             ServerWebExchange exchange) {
        String identifier = trash_id == null || trash_id.isBlank() ? path : trash_id;
        return authenticated(exchange, owner -> files.restoreTrash(owner, identifier));
    }

    /**
     * 响应 {@code POST /api/v1/files/trash/empty}，清空当前用户回收站。
     *
     * @param exchange 用于限定回收站 owner 的请求上下文。
     * @return 存储服务返回的清理结果。
     */
    @PostMapping("/trash/empty")
    public Mono<Map<String, Object>> emptyTrash(ServerWebExchange exchange) {
        return authenticated(exchange, files::emptyTrash);
    }

    /**
     * 接收 multipart 文件并响应 {@code POST /api/v1/files/upload}。
     *
     * <p>请求体先流式写入受保护的临时文件，随后由存储服务重新计算 MD5、按
     * {@code noclobber} 规则原子发布，并登记文件 metadata；临时文件在终止信号
     * 到来时清理，客户端提供的 MD5 不直接作为可信结果。
     *
     * @param file multipart 中名为 {@code file} 的上传内容。
     * @param path 目标目录相对路径，空值表示 owner 根目录。
     * @param md5 客户端预检用 MD5，最终仍由服务端复算验证。
     * @param noclobber 为 {@code true} 时同名文件按存储层规则生成不覆盖的新名称。
     * @param exchange 用于确定上传 owner 的请求上下文。
     * @return 发布后的文件元数据和上传结果。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> upload(@RequestPart("file") FilePart file,
                                            @RequestParam(defaultValue = "") String path,
                                            @RequestParam(defaultValue = "") String md5,
                                            @RequestParam(defaultValue = "false") boolean noclobber,
                                            ServerWebExchange exchange) {
        return principalResolver.resolve(exchange).flatMap(principal -> {
            Path temp = files.createUploadTemp();
            return file.transferTo(temp)
                    .then(ReactiveExecution.blocking(() -> files.publishUpload(
                            principal.userId(), path, file.filename(), temp, md5, noclobber)))
                    .doFinally(signal -> files.discardTemp(temp));
        });
    }

    /**
     * 接收共享上传并响应 {@code POST /api/v1/files/upload-share}。
     *
     * <p>该端点把上传发布到 owner 根目录并启用不覆盖策略，成功后返回 303，
     * 将浏览器重定向到带新文件路径的前端页面。
     *
     * @param file multipart 中名为 {@code file} 的上传内容。
     * @param exchange 用于确定上传 owner 的请求上下文。
     * @return 成功时为指向前端共享结果页的 303 响应。
     */
    @PostMapping(value = "/upload-share", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> uploadShare(@RequestPart("file") FilePart file,
                                                   ServerWebExchange exchange) {
        return principalResolver.resolve(exchange).flatMap(principal -> {
            Path temp = files.createUploadTemp();
            return file.transferTo(temp)
                    .then(ReactiveExecution.blocking(() -> files.publishUpload(
                            principal.userId(), "", file.filename(), temp, "", true)))
                    .map(result -> ResponseEntity.<Void>status(303)
                            .header(HttpHeaders.LOCATION, "/?shared=" + String.valueOf(((Map<?, ?>) result.get("uploaded")).get("path")))
                            .build())
                    .doFinally(signal -> files.discardTemp(temp));
        });
    }

    /**
     * 认证请求并在后台调度器执行 owner-scoped 存储操作。
     *
     * @param exchange 用于解析当前用户的请求上下文。
     * @param action 接收已解析用户 ID 并执行单次存储操作的函数。
     * @param <T> 存储操作结果类型。
     * @return 认证成功后异步执行 action 的 {@link Mono}。
     */
    private <T> Mono<T> authenticated(ServerWebExchange exchange, Function<UUID, T> action) {
        return principalResolver.resolve(exchange)
                .flatMap(principal -> ReactiveExecution.blocking(() -> action.apply(principal.userId())));
    }

    /**
     * 解析媒体凭据、打开文件并构造内联或附件响应。
     *
     * @param exchange 用于解析 Cookie/Bearer/查询令牌的请求上下文。
     * @param path owner 相对文件路径；实际读取前由存储服务执行安全解析。
     * @param download 是否添加附件下载的 Content-Disposition。
     * @return 带探测媒体类型的文件资源响应。
     */
    private Mono<ResponseEntity<Resource>> media(ServerWebExchange exchange, String path, boolean download) {
        return principalResolver.resolveMedia(exchange)
                .flatMap(principal -> ReactiveExecution.blocking(() -> {
                    Path file = files.fileForRead(principal.userId(), path);
                    files.touchAccess(principal.userId(), path);
                    String contentType = Files.probeContentType(file);
                    String normalizedType = contentType == null ? ""
                            : contentType.toLowerCase(Locale.ROOT).trim();
                    boolean inline = INLINE_MEDIA_TYPES.contains(normalizedType);
                    Resource resource = inline
                            ? new FileSystemResource(file)
                            : new OpaqueFileSystemResource(file);
                    MediaType mediaType = inline
                            ? MediaType.parseMediaType(normalizedType)
                            : MediaType.APPLICATION_OCTET_STREAM;
                    ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                            .contentType(mediaType)
                            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                            .header("X-Content-Type-Options", "nosniff")
                            .header("Referrer-Policy", "no-referrer")
                            .header("Content-Security-Policy", "sandbox; default-src 'none'")
                            .header("Cross-Origin-Resource-Policy", "same-origin");
                    if (download || !inline) {
                        response.header(HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.attachment().filename(file.getFileName().toString()).build().toString());
                    }
                    return response.body(resource);
                }));
    }

    /**
     * 隐藏资源文件名，防止 WebFlux 在显式 octet-stream 响应上按扩展名重新推断活动内容类型。
     * 真实下载名仍由控制器写入的 {@code Content-Disposition} 提供。
     */
    private static final class OpaqueFileSystemResource extends FileSystemResource {
        /**
         * 创建指向真实文件、但不向消息编码器暴露文件名的资源。
         * @param path 要流式读取的文件路径。
         */
        private OpaqueFileSystemResource(Path path) {
            super(path);
        }

        /**
         * 禁止消息编码器根据扩展名覆盖控制器选择的媒体类型。
         * @return 始终为 null。
         */
        @Override
        public String getFilename() {
            return null;
        }
    }
}
