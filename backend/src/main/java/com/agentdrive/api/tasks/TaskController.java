package com.agentdrive.api.tasks;

import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.tasks.IndexTaskPaths;
import com.agentdrive.tasks.TaskStore;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 提供 owner-scoped 任务查询、索引任务入队、状态变更和任务事件 SSE。
 *
 * <p>HTTP 请求只负责校验参数并向 {@link TaskStore} 入队或读取状态，抽取、向量化
 * 等耗时工作由独立 Worker 执行。列表接口限制页大小为 1..200；事件流从请求游标
 * 或 {@code Last-Event-ID} 继续，最长保持 55 秒，并在没有新事件时发送 SSE comment
 * 作为 keepalive。
 */
@RestController
@Profile({"java-auth", "java-chat"})
@RequestMapping("/api/v1/tasks")
public final class TaskController {
    private static final int HISTORY_RETENTION_DAYS = 30;
    private static final int HISTORY_KEEP_RECENT = 2000;
    private static final Set<String> STATUSES = Set.of(
            "queued", "running", "retry_wait", "cancelling", "cancelled", "succeeded", "failed"
    );

    private final TaskStore tasks;
    private final WebRequestPrincipalResolver principalResolver;

    /**
     * 创建任务 API 控制器。
     *
     * @param tasks 读取 owner-scoped 任务、写入任务状态和事件的持久化服务。
     * @param principalResolver 将请求凭据解析为任务 owner。
     */
    public TaskController(TaskStore tasks, WebRequestPrincipalResolver principalResolver) {
        this.tasks = tasks;
        this.principalResolver = principalResolver;
    }

    /**
     * 响应 {@code GET /api/v1/tasks}，按状态、类型和分页参数列出任务。
     *
     * @param status 逗号分隔的任务状态筛选；只能使用 queued、running、retry_wait、cancelling、cancelled、succeeded、failed。
     * @param taskType 任务类型筛选，例如 {@code index.embed}，为空表示不过滤。
     * @param includeChildren 是否在列表项中包含子任务信息。
     * @param limit 返回条数，必须在 1 到 200 之间。
     * @param offset 分页偏移，不能为负数。
     * @param exchange 用于限定任务 owner 的请求上下文。
     * @return 当前页 {@code items}、是否还有下一页的 {@code has_more} 与 owner 级任务 {@code overview}。
     * @throws ResponseStatusException 参数状态未知或分页越界时返回 400。
     */
    @GetMapping
    public Mono<Map<String, Object>> list(
            @RequestParam(defaultValue = "") String status,
            @RequestParam(name = "task_type", defaultValue = "") String taskType,
            @RequestParam(defaultValue = "false") boolean includeChildren,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            ServerWebExchange exchange
    ) {
        List<String> statuses = parseStatuses(status);
        if (limit < 1 || limit > 200 || offset < 0) throw new ResponseStatusException(BAD_REQUEST, "invalid pagination");
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() -> {
            // 多取一条只用于判断是否还有下一页，响应仍严格遵守调用方要求的 limit。
            List<Map<String, Object>> rows = tasks.list(
                    principal.userId(), statuses, taskType, includeChildren, limit + 1, offset
            );
            boolean hasMore = rows.size() > limit;
            List<Map<String, Object>> items = hasMore ? rows.subList(0, limit) : rows;
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("items", items);
            response.put("has_more", hasMore);
            response.put("overview", tasks.overview(principal.userId()));
            return response;
        }));
    }

    /**
     * 响应 {@code GET /api/v1/tasks/summary}，返回当前用户的任务总览计数。
     *
     * @param exchange 用于确定任务 owner 的请求上下文。
     * @return {@link TaskStore} 生成的顶层任务统计；子任务由存储层汇总。
     */
    @GetMapping("/summary")
    public Mono<Map<String, Object>> summary(ServerWebExchange exchange) {
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() -> tasks.overview(principal.userId())));
    }

    /**
     * 响应 {@code POST /api/v1/tasks/prune-history}，清理当前用户可安全回收的历史任务。
     *
     * <p>清理策略由服务端固定：只处理超过 30 天的终态任务，至少保留最近 2000 条，
     * 运行中任务以及仍被子任务引用的父任务由存储层保护。该操作不会创建新的任务记录，
     * 避免“清理任务”本身再次制造无法清理的历史。</p>
     *
     * @param exchange 用于限定清理范围到当前任务 owner 的请求上下文。
     * @return 清理数量和当前生效的历史保留策略。
     */
    @PostMapping("/prune-history")
    public Mono<Map<String, Object>> pruneHistory(ServerWebExchange exchange) {
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() -> {
            Map<String, Object> result = new LinkedHashMap<>(tasks.pruneHistory(
                    principal.userId(), HISTORY_RETENTION_DAYS, HISTORY_KEEP_RECENT
            ));
            // TaskStore 的内部/维护任务契约使用 jobs；HTTP 契约提供更直观的 removed 别名。
            result.put("removed", result.getOrDefault("jobs", 0));
            return result;
        }));
    }

    /**
     * 响应 {@code POST /api/v1/tasks/clear-terminal}，清理当前用户所有已结束任务。
     *
     * <p>这是用户主动清理入口，不使用自动维护的 30 天/2000 条保留策略。活动任务、
     * 以及仍有活动后代的父任务由存储层保护；删除任务不会创建新的任务记录。</p>
     *
     * @param exchange 用于限定清理范围到当前任务 owner 的请求上下文。
     * @return 实际删除的任务和子任务记录数量。
     */
    @PostMapping("/clear-terminal")
    public Mono<Map<String, Object>> clearTerminal(ServerWebExchange exchange) {
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() -> {
            int removed = tasks.clearTerminal(principal.userId());
            return Map.of("removed", removed, "jobs", removed, "events", 0, "workers", 0);
        }));
    }

    /**
     * 响应 {@code POST /api/v1/tasks/rebuild-index}，只入队全量或前缀索引重建任务。
     *
     * @param request 可选的文件前缀和 force 标志；请求体缺失时使用根路径且不强制重建。
     * @param exchange 用于确定任务 owner 的请求上下文。
     * @return {@code queued} 是否新建任务以及任务记录；实际重建由 Worker 执行。
     */
    @PostMapping("/rebuild-index")
    public Mono<Map<String, Object>> rebuild(@RequestBody(required = false) RebuildRequest request,
                                               ServerWebExchange exchange) {
        RebuildRequest body = request == null ? new RebuildRequest("", false) : request;
        String prefix = body.prefix() == null ? "" : body.prefix();
        Map<String, Object> payload = Map.of("prefix", prefix, "force", body.force());
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() -> {
            TaskStore.EnqueueResult result = tasks.enqueue(
                    principal.userId(), "index.rebuild", "index", payload,
                    "index.rebuild:" + prefix + ":" + body.force(), "api", null
            );
            return queued(result);
        }));
    }

    /**
     * 响应 {@code POST /api/v1/tasks/embed-index}，为指定文件列表入队向量化任务。
     *
     * <p>文件列表由 {@link IndexTaskPaths} 去重并校验为 owner 根目录下的相对路径，
     * 请求不会在 API 线程执行抽取或 embedding；Worker 会按任务 payload 处理并用
     * {@code force} 决定是否忽略已有向量。
     *
     * @param request 包含文件相对路径列表和可选 force 标志的请求体。
     * @param exchange 用于确定任务 owner 的请求上下文。
     * @return {@code queued} 是否创建新任务以及任务记录。
     */
    @PostMapping("/embed-index")
    public Mono<Map<String, Object>> embedIndex(@RequestBody(required = false) EmbedRequest request,
                                                 ServerWebExchange exchange) {
        List<String> files = normalizeFiles(request == null ? null : request.files());
        boolean force = request != null && request.force();
        Map<String, Object> payload = Map.of("files", files, "force", force);
        String dedupeKey = IndexTaskPaths.dedupeKey(files, force);
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() -> {
            TaskStore.EnqueueResult result = tasks.enqueue(
                    principal.userId(), "index.embed", "index", payload,
                    dedupeKey, "api", null
            );
            return queued(result);
        }));
    }

    /**
     * 响应 {@code POST /api/v1/tasks/vision-index}，为指定图片列表排队视觉描述和向量化。
     *
     * <p>任务先由 Worker 调用视觉模型生成固定 schema 描述，再把描述写入当前文件 revision
     * 的文档 chunks，最后复用现有 embedding provider 写入 pgvector；API 请求本身不会读取图片
     * 或调用外部模型。</p>
     *
     * @param request 包含 owner 相对图片路径列表和可选 force 的请求体。
     * @param exchange 用于确定任务 owner 的请求上下文。
     * @return {@code queued} 是否创建任务以及任务记录。
     */
    @PostMapping("/vision-index")
    public Mono<Map<String, Object>> visionIndex(@RequestBody(required = false) VisionIndexRequest request,
                                                  ServerWebExchange exchange) {
        List<String> files = normalizeVisionFiles(request == null ? null : request.files());
        boolean force = request != null && request.force();
        Map<String, Object> payload = Map.of("files", files, "force", force);
        String dedupeKey = "index.vision:" + IndexTaskPaths.dedupeKey(files, force);
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() -> {
            TaskStore.EnqueueResult result = tasks.enqueue(
                    principal.userId(), "index.vision", "index", payload,
                    dedupeKey, "api", null
            );
            return queued(result);
        }));
    }

    /**
     * 响应 {@code POST /api/v1/tasks/cleanup-index}，入队清理过期索引任务。
     *
     * @param exchange 用于确定任务 owner 的请求上下文。
     * @return {@code queued} 标志和清理任务记录；清理动作由 Worker 异步执行。
     */
    @PostMapping("/cleanup-index")
    public Mono<Map<String, Object>> cleanup(ServerWebExchange exchange) {
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() -> {
            TaskStore.EnqueueResult result = tasks.enqueue(
                    principal.userId(), "index.cleanup", "index", Map.of(),
                    "index.cleanup", "api", null
            );
            Map<String, Object> response = queued(result);
            response.put("message", "后台失效索引清理已提交，不会清空当前文本或视觉向量");
            return response;
        }));
    }

    /**
     * 响应 {@code POST /api/v1/tasks/clear-vectors}，后台清空当前 owner 的全部向量值。
     *
     * <p>该操作只删除 text/vision embedding 和 fingerprint，不删除原文件、正文索引或
     * 视觉描述；由于会扫描并更新 owner 全部 chunk，必须交给 Worker 执行。</p>
     *
     * @param exchange 用于限定清空范围到当前任务 owner 的请求上下文。
     * @return {@code queued} 标志和后台任务记录。
     */
    @PostMapping("/clear-vectors")
    public Mono<Map<String, Object>> clearVectors(ServerWebExchange exchange) {
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() -> {
            TaskStore.EnqueueResult result = tasks.enqueue(
                    principal.userId(), "index.clear_vectors", "index", Map.of(),
                    "index.clear_vectors", "api", null
            );
            Map<String, Object> response = queued(result);
            response.put("message", "后台向量清空已提交，不会删除原文件或正文索引");
            return response;
        }));
    }

    /** 统一返回后台任务提交结果，明确 queued 不等于业务已完成。 */
    private Map<String, Object> queued(TaskStore.EnqueueResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("queued", result.created());
        response.put("execution_mode", "background");
        response.put("message", result.created()
                ? "后台任务已提交，请通过 task.id 查询完成状态"
                : "已有相同后台任务在运行，请通过 task.id 查询完成状态");
        response.put("task", result.task());
        return response;
    }

    /**
     * 响应 {@code GET /api/v1/tasks/events}，以 SSE 推送当前用户的新任务事件。
     *
     * <p>起始游标取 {@code after} 与 {@code Last-Event-ID} 中较大者；两者都没有有效
     * 值时从当前事件尾部开始，避免首次订阅回放历史。每秒查询一次、每批最多 100 条，
     * 55 秒后结束连接让客户端重新订阅。
     *
     * @param after 查询参数指定的最后事件 ID，可为空。
     * @param lastEventId 浏览器 SSE 重连头，可为空或非数字。
     * @param exchange 用于确定事件 owner 的请求上下文。
     * @return 带事件 ID 和 {@code task} 事件名的 SSE 流；无新事件时发送 keepalive comment。
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> events(
            @RequestParam(required = false) Long after,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            ServerWebExchange exchange
    ) {
        return principalResolver.resolve(exchange).flatMapMany(principal -> {
            long cursor = Math.max(after == null ? 0 : after, parseEventId(lastEventId));
            if (after == null && (lastEventId == null || !lastEventId.matches("\\d+"))) {
                cursor = tasks.latestEventId(principal.userId());
            }
            AtomicLong next = new AtomicLong(cursor);
            return Flux.interval(Duration.ZERO, Duration.ofSeconds(1))
                    .take(Duration.ofSeconds(55))
                    .flatMap(tick -> Mono.fromCallable(() -> tasks.events(principal.userId(), next.get(), 100))
                            .subscribeOn(Schedulers.boundedElastic()))
                    .flatMapIterable(this::withKeepalive)
                    .map(item -> {
                        if (Boolean.TRUE.equals(item.get("__keepalive"))) {
                            return ServerSentEvent.<Map<String, Object>>builder().comment("keepalive").build();
                        }
                        next.set(((Number) item.get("id")).longValue());
                        return ServerSentEvent.<Map<String, Object>>builder(item)
                                .id(String.valueOf(item.get("id")))
                                .event("task")
                                .build();
                    });
        });
    }

    /**
     * 响应 {@code GET /api/v1/tasks/{taskId} }，返回任务及其子任务汇总。
     *
     * @param taskId 任务 UUID。
     * @param exchange 用于限定查询到当前用户的请求上下文。
     * @return 包含 {@code task} 和 {@code children} 的详情响应。
     * @throws ResponseStatusException 任务不存在时返回 404。
     */
    @GetMapping("/{taskId}")
    public Mono<Map<String, Object>> detail(@PathVariable UUID taskId, ServerWebExchange exchange) {
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() -> {
            Map<String, Object> task = tasks.get(principal.userId(), taskId);
            if (task == null) throw new ResponseStatusException(NOT_FOUND, "任务不存在");
            return Map.of("task", task, "children", tasks.childSummary(principal.userId(), taskId));
        }));
    }

    /**
     * 响应 {@code POST /api/v1/tasks/{taskId}/cancel}，请求取消当前用户的任务。
     *
     * @param taskId 待取消任务的 UUID。
     * @param exchange 用于限定变更到当前用户的请求上下文。
     * @return 任务进入取消流程后的任务记录。
     * @throws ResponseStatusException 任务不存在时返回 404；具体状态迁移由 {@link TaskStore} 决定。
     */
    @PostMapping("/{taskId}/cancel")
    public Mono<Map<String, Object>> cancel(@PathVariable UUID taskId, ServerWebExchange exchange) {
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() -> {
            TaskStore.TransitionResult result = tasks.cancel(principal.userId(), taskId);
            if (result.task() == null) throw new ResponseStatusException(NOT_FOUND, "任务不存在");
            return Map.of("task", result.task(), "changed", result.changed());
        }));
    }

    /**
     * 响应 {@code POST /api/v1/tasks/{taskId}/retry}，重新排队失败或已取消任务。
     *
     * @param taskId 待重试任务的 UUID。
     * @param exchange 用于限定变更到当前用户的请求上下文。
     * @return 重新排队后的任务记录。
     * @throws ResponseStatusException 任务不存在时返回 404，任务状态不允许重试时返回 409。
     */
    @PostMapping("/{taskId}/retry")
    public Mono<Map<String, Object>> retry(@PathVariable UUID taskId, ServerWebExchange exchange) {
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() -> {
            TaskStore.TransitionResult result = tasks.retry(principal.userId(), taskId);
            if (result.task() == null) throw new ResponseStatusException(NOT_FOUND, "任务不存在");
            if (!result.changed()) {
                throw new ResponseStatusException(CONFLICT, "只有失败或已取消的任务可以重试");
            }
            return Map.of("task", result.task(), "changed", true);
        }));
    }

    /**
     * 响应 {@code DELETE /api/v1/tasks/{taskId} }，删除一条已结束任务或任务组。
     *
     * @param taskId 要删除的任务 UUID。
     * @param exchange 用于限定变更到当前用户的请求上下文。
     * @return 删除数量；父任务若有活动后代则不会删除任何记录。
     * @throws ResponseStatusException 任务不存在时返回 404，任务未结束或仍有活动后代时返回 409。
     */
    @DeleteMapping("/{taskId}")
    public Mono<Map<String, Object>> delete(@PathVariable UUID taskId, ServerWebExchange exchange) {
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() -> {
            TaskStore.DeleteResult result = tasks.delete(principal.userId(), taskId);
            if (result == null) throw new ResponseStatusException(NOT_FOUND, "任务不存在");
            if (!result.deleted()) {
                String detail = "active_children".equals(result.reason())
                        ? "该任务仍有进行中的子任务，完成后才能删除"
                        : "只有已结束的任务可以删除";
                throw new ResponseStatusException(CONFLICT, detail);
            }
            return Map.of("task_id", taskId.toString(), "removed", result.removed(), "jobs", result.removed());
        }));
    }

    /**
     * 解析任务状态筛选字符串并检查每个状态是否在允许集合内。
     *
     * @param raw 逗号分隔的状态文本；空值表示不过滤。
     * @return 去除空项并保持首次出现顺序的唯一状态列表。
     * @throws ResponseStatusException 包含未知状态时返回 400。
     */
    private List<String> parseStatuses(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> values = Arrays.stream(raw.split(","))
                .filter(value -> !value.isBlank()).distinct().toList();
        if (!STATUSES.containsAll(values)) throw new ResponseStatusException(BAD_REQUEST, "无效任务状态");
        return values;
    }

    /**
     * 将向量化请求中的路径列表交给统一路径规则规范化。
     *
     * @param files 请求中的 owner 相对文件路径列表；不能为空，且每项必须是 owner 根下的相对路径。
     * @return 去重后的规范化路径列表。
     * @throws ResponseStatusException 路径越界、内部路径或数量不合法时返回 400。
     */
    private List<String> normalizeFiles(List<String> files) {
        try {
            return IndexTaskPaths.normalize(files);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(BAD_REQUEST, error.getMessage());
        }
    }

    /**
     * 规范化视觉索引路径，并拒绝空列表或超过 100 项的同步图片任务输入。
     * @param files 请求中的 owner 相对图片路径列表。
     * @return 去重后的安全路径列表。
     * @throws ResponseStatusException 输入不符合视觉任务规模或路径规则时返回 400。
     */
    private List<String> normalizeVisionFiles(List<String> files) {
        if (files == null || files.isEmpty() || files.size() > 100) {
            throw new ResponseStatusException(BAD_REQUEST, "vision files must contain 1 to 100 paths");
        }
        try {
            return IndexTaskPaths.normalize(files);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(BAD_REQUEST, error.getMessage());
        }
    }

    /**
     * 为无事件的轮询结果生成一个 keepalive 哨兵。
     *
     * @param items 当前游标之后读取到的事件。
     * @return 原列表非空时原样返回；为空时返回带 {@code __keepalive=true} 的单项列表。
     */
    private List<Map<String, Object>> withKeepalive(List<Map<String, Object>> items) {
        if (!items.isEmpty()) return items;
        Map<String, Object> keepalive = new LinkedHashMap<>();
        keepalive.put("__keepalive", true);
        return List.of(keepalive);
    }

    /**
     * 解析 SSE 事件 ID 头。
     *
     * @param value {@code Last-Event-ID} 原文。
     * @return 纯数字时转换出的非负游标，否则返回 0。
     */
    private long parseEventId(String value) {
        return value != null && value.matches("\\d+") ? Long.parseLong(value) : 0L;
    }

    /**
     * 将任务数据库的同步查询/写入包装到 bounded-elastic 调度器。
     *
     * @param operation 要执行的任务存储操作。
     * @param <T> 操作结果类型。
     * @return 异步任务操作结果。
     */
    private <T> Mono<T> blocking(java.util.concurrent.Callable<T> operation) {
        return Mono.fromCallable(operation).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 索引重建入队请求；{@code prefix} 限定重建范围，{@code force} 控制是否忽略有效索引。
     */
    public record RebuildRequest(String prefix, boolean force) {
    }

    /**
     * 文件向量化入队请求；{@code files} 是 owner 根目录下的相对文件路径列表，
     * {@code force} 控制是否重新生成已有向量。
     */
    public record EmbedRequest(List<String> files, boolean force) {
    }

    /**
     * 图片视觉索引任务请求；files 必须是 owner 根目录下的相对路径列表。
     * @param files 待识别的图片路径。
     * @param force 是否忽略已有当前模型向量并重新识别、向量化。
     */
    public record VisionIndexRequest(List<String> files, boolean force) {
    }
}
