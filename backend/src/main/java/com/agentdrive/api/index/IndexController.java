package com.agentdrive.api.index;

import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.index.IndexDomainService;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** Owner-scoped 索引资源 CRUD 和直接业务操作 API。 */
@RestController
@Profile({"java-files", "java-auth", "java-chat"})
@RequestMapping("/api/v1/index")
public final class IndexController {
    private final IndexDomainService index;
    private final WebRequestPrincipalResolver principalResolver;

    public IndexController(IndexDomainService index, WebRequestPrincipalResolver principalResolver) {
        this.index = index;
        this.principalResolver = principalResolver;
    }

    /** 查询 owner 索引概览和文件索引资源。 */
    @GetMapping
    public Mono<Map<String, Object>> overview(
            @RequestParam(defaultValue = "") String prefix,
            @RequestParam(defaultValue = "200") int limit,
            ServerWebExchange exchange) {
        if (limit < 1 || limit > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 1000");
        return authenticated(exchange, owner -> index.overview(owner, prefix, limit));
    }

    /** 查询单个文件的索引元数据。 */
    @GetMapping("/file")
    public Mono<Map<String, Object>> file(@RequestParam String path, ServerWebExchange exchange) {
        return authenticated(exchange, owner -> index.file(owner, path));
    }

    /** 直接抽取并写入单个文本文件索引。 */
    @PutMapping("/file")
    public Mono<Map<String, Object>> indexFile(@RequestBody(required = false) IndexRequest request,
                                                ServerWebExchange exchange) {
        return authenticated(exchange, owner -> index.indexFiles(owner, requiredPaths(request), request != null && request.force()));
    }

    /** 直接识别、写入视觉描述并向量化图片；多图片由任务模块记录执行状态。 */
    @PutMapping("/vision")
    public Mono<Map<String, Object>> indexVision(@RequestBody(required = false) IndexRequest request,
                                                  ServerWebExchange exchange) {
        return authenticated(exchange, owner -> index.indexVision(owner, requiredPaths(request), request != null && request.force()));
    }

    /** 直接向量化当前文档块；paths 为空时覆盖 owner 全部待处理文档。 */
    @PutMapping("/vectors")
    public Mono<Map<String, Object>> vectorize(@RequestBody(required = false) VectorRequest request,
                                                ServerWebExchange exchange) {
        VectorRequest body = request == null ? new VectorRequest(List.of(), false, 64) : request;
        return authenticated(exchange, owner -> index.vectorize(owner,
                body.paths() == null ? List.of() : body.paths(), body.force(), body.limit()));
    }

    /** 直接删除当前 owner 的文本/视觉向量，保留原文件和文档正文。 */
    @DeleteMapping("/vectors")
    public Mono<Map<String, Object>> clearVectors(ServerWebExchange exchange) {
        return authenticated(exchange, index::clearVectors);
    }

    /** 删除当前 owner 的失效索引文档。 */
    @DeleteMapping("/stale")
    public Mono<Map<String, Object>> cleanup(ServerWebExchange exchange) {
        return authenticated(exchange, index::cleanup);
    }

    /** 直接重建指定前缀的全文索引。 */
    @PostMapping("/rebuild")
    public Mono<Map<String, Object>> rebuild(@RequestBody(required = false) PrefixRequest request,
                                              ServerWebExchange exchange) {
        return authenticated(exchange, owner -> index.rebuild(owner, request == null ? "" : request.prefix()));
    }

    private List<String> requiredPaths(IndexRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paths are required");
        if (request.paths() != null && !request.paths().isEmpty()) return request.paths();
        if (request.path() != null && !request.path().isBlank()) return List.of(request.path());
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path or paths is required");
    }

    private <T> Mono<T> authenticated(ServerWebExchange exchange,
                                      java.util.function.Function<java.util.UUID, T> operation) {
        return principalResolver.resolve(exchange)
                .flatMap(principal -> blocking(() -> operation.apply(principal.userId())))
                .onErrorMap(IllegalArgumentException.class, error ->
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error));
    }

    private <T> Mono<T> blocking(Callable<T> operation) {
        return Mono.fromCallable(operation).subscribeOn(Schedulers.boundedElastic());
    }

    public record IndexRequest(@JsonProperty("path") String path,
                               @JsonProperty("paths") List<String> paths,
                               @JsonProperty("force") boolean force) {
    }

    public record PrefixRequest(@JsonProperty("prefix") String prefix) {
    }

    public record VectorRequest(@JsonProperty("paths") List<String> paths,
                                @JsonProperty("force") boolean force,
                                @JsonProperty("limit") int limit) {
        public VectorRequest {
            paths = paths == null ? List.of() : List.copyOf(paths);
            if (limit <= 0) limit = 64;
        }
    }
}
