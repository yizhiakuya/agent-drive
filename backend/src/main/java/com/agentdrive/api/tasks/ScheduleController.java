package com.agentdrive.api.tasks;

import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.tasks.ScheduleStore;
import com.agentdrive.tasks.TaskStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 提供 owner-scoped 自动化计划的查询、创建/更新和删除 API。
 *
 * <p>计划定义由 {@link ScheduleStore} 持久化，包含 cron、interval 或 daily 调度参数、
 * 任务类型、lane、payload、优先级和最大尝试次数；到期后只由 Worker 派发任务，不在
 * HTTP 请求中执行 payload。控制器只负责认证、请求体存在性检查以及把阻塞式存储调用
 * 移到 bounded-elastic。
 */
@RestController
@Profile({"java-auth", "java-chat"})
@RequestMapping("/api/v1/schedules")
public final class ScheduleController {
    private final ScheduleStore schedules;
    private final TaskStore tasks;
    private final WebRequestPrincipalResolver principalResolver;

    /**
     * 创建计划 API 控制器。
     *
     * @param schedules 读写 owner-scoped 计划定义的存储服务。
     * @param principalResolver 将请求凭据解析为计划 owner。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ScheduleController(ScheduleStore schedules, TaskStore tasks,
                              WebRequestPrincipalResolver principalResolver) {
        this.schedules = schedules;
        this.tasks = tasks;
        this.principalResolver = principalResolver;
    }

    /**
     * 保留给轻量 controller contract test 和旧嵌入调用方的构造器；立即运行能力在
     * 没有任务存储时明确返回服务不可用，而列表/保存/删除仍可独立测试。
     */
    public ScheduleController(ScheduleStore schedules, WebRequestPrincipalResolver principalResolver) {
        this(schedules, null, principalResolver);
    }

    /**
     * 响应 {@code GET /api/v1/schedules}，列出当前用户的计划定义。
     *
     * @param exchange 用于解析计划 owner 的请求上下文。
     * @return 包含 {@code schedules} 列表的异步 JSON 响应。
     */
    @GetMapping
    public Mono<Map<String, Object>> list(ServerWebExchange exchange) {
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() ->
                Map.of("schedules", schedules.list(principal.userId()))));
    }

    /**
     * 响应 {@code PUT /api/v1/schedules/{name} }，创建或替换一个计划定义。
     *
     * @param name 计划的稳定名称，作为 owner 内的唯一键。
     * @param request 计划表达式、任务类型、payload 和执行策略；不能为 {@code null}。
     * @param exchange 用于限定写入 owner 的请求上下文。
     * @return 持久化后的计划记录。
     * @throws ResponseStatusException 请求体缺失时返回 400。
     */
    @PutMapping("/{name}")
    public Mono<Map<String, Object>> upsert(@PathVariable String name,
                                             @Valid @RequestBody ScheduleRequest request,
                                             ServerWebExchange exchange) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schedule body is required");
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() -> Map.<String, Object>of(
                "schedule", schedules.upsert(principal.userId(), name, request.cron(), request.scheduleKind(),
                        request.scheduleValue(), request.taskType(), request.lane(), request.payload(),
                        request.enabled(), request.priority(), request.maxAttempts(), request.timezone())
        ))).onErrorMap(IllegalArgumentException.class, error ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error));
    }

    /**
     * 响应 {@code DELETE /api/v1/schedules/{name} }，删除当前用户的计划。
     *
     * @param name owner 内的计划名称。
     * @param exchange 用于限定删除 owner 的请求上下文。
     * @return 包含被删除计划名称的响应。
     * @throws ResponseStatusException 计划不存在时返回 404。
     */
    @DeleteMapping("/{name}")
    public Mono<Map<String, Object>> delete(@PathVariable String name, ServerWebExchange exchange) {
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() -> {
            if (!schedules.delete(principal.userId(), name)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "schedule not found");
            }
            return Map.of("deleted", name);
        }));
    }

    /**
     * 响应 {@code POST /api/v1/schedules/{name}/run}，立即为当前 owner 入队一次自动化。
     *
     * <p>该入口只创建持久化 {@code automation.run} 任务，不在请求线程执行规则；规则的
     * payload、优先级和最大尝试次数取自当前 owner 的计划定义。每次手动运行使用一次性
     * 去重键，允许用户在上一轮仍运行时再次明确触发。</p>
     *
     * @param name owner 内的计划名称。
     * @param exchange 用于限定读取和入队 owner 的请求上下文。
     * @return {@code queued} 标志及任务快照。
     */
    @PostMapping("/{name}/run")
    public Mono<Map<String, Object>> run(@PathVariable String name, ServerWebExchange exchange) {
        if (tasks == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "task store unavailable"));
        }
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() -> {
            Map<String, Object> schedule = schedules.list(principal.userId()).stream()
                    .filter(item -> name.equals(String.valueOf(item.get("name"))))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "schedule not found"));

            Map<String, Object> payload = new LinkedHashMap<>();
            Object configured = schedule.get("payload");
            if (configured instanceof Map<?, ?> values) {
                values.forEach((key, value) -> {
                    if (key != null) payload.put(String.valueOf(key), value);
                });
            }
            payload.put("schedule_name", name);
            String dedupeKey = "automation.manual:" + name + ":" + UUID.randomUUID();
            int priority = number(schedule.get("priority"), 0);
            int maxAttempts = Math.max(1, number(schedule.get("max_attempts"), 3));
            TaskStore.EnqueueResult result = tasks.enqueue(
                    principal.userId(), "automation.run", "automation", payload,
                    dedupeKey, "api", null, priority, maxAttempts);
            schedules.markManualRun(principal.userId(), name, String.valueOf(result.task().get("id")));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("queued", result.created());
            response.put("task", result.task());
            response.put("schedule", name);
            return response;
        }));
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * 将计划存储调用移到 bounded-elastic 调度器。
     *
     * @param operation 要执行的计划查询或变更。
     * @param <T> 操作结果类型。
     * @return 异步计划操作结果。
     */
    private <T> Mono<T> blocking(java.util.concurrent.Callable<T> operation) {
        return Mono.fromCallable(operation).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 计划写入请求体，描述触发方式、目标任务及执行参数。
     *
     * <p>{@code scheduleKind}/{@code scheduleValue} 由调度存储解释；payload 原样作为
     * 任务输入持久化，enabled、priority 和 maxAttempts 控制后续 Worker 行为。
     */
    public record ScheduleRequest(
            @Size(max = 200) String cron,
            @Pattern(regexp = "cron|interval|daily") String scheduleKind,
            @Size(max = 200) String scheduleValue,
            @NotBlank @Size(max = 100) String taskType,
            @Size(max = 100) String lane,
            Map<String, Object> payload,
            boolean enabled,
            @Min(0) int priority,
            @Min(1) int maxAttempts,
            @Size(max = 100) String timezone
    ) {
    }
}
