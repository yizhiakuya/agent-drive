package com.agentdrive.api.chat;

import com.agentdrive.agent.ChatRunStateStore;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 将聊天 runtime 订阅与单个 HTTP/SSE 客户端解耦。
 * 客户端断开只会卸载 relay subscriber；runtime 继续运行并把结果写入 transcript。
 */
@Component
public final class ChatRunRegistry implements AutoCloseable {
    private static final int DEFAULT_MAX_ACTIVE_RUNS = 8;
    private static final Duration DEFAULT_RUN_TIMEOUT = Duration.ofMinutes(10);
    private final Map<String, ActiveRun> runs = new ConcurrentHashMap<>();
    private final int maxActiveRuns;
    private final Duration runTimeout;
    private final ChatRunStateStore stateStore;

    /** 使用生产默认并发上限和单次运行时限。 */
    public ChatRunRegistry() {
        this(DEFAULT_MAX_ACTIVE_RUNS, DEFAULT_RUN_TIMEOUT, ChatRunStateStore.noop());
    }

    /** 创建生产注册表并连接 owner-scoped 的持久 run 状态。 */
    @Autowired
    public ChatRunRegistry(ChatRunStateStore stateStore) {
        this(DEFAULT_MAX_ACTIVE_RUNS, DEFAULT_RUN_TIMEOUT, stateStore);
    }

    /**
     * 创建可测试的聊天运行注册表。
     * @param maxActiveRuns 进程内允许的最大并行 Agent 数
     * @param runTimeout 单次 Agent 运行的最大时长
     */
    ChatRunRegistry(int maxActiveRuns, Duration runTimeout) {
        this(maxActiveRuns, runTimeout, ChatRunStateStore.noop());
    }

    ChatRunRegistry(int maxActiveRuns, Duration runTimeout, ChatRunStateStore stateStore) {
        if (maxActiveRuns < 1) throw new IllegalArgumentException("maxActiveRuns must be positive");
        if (runTimeout == null || runTimeout.isZero() || runTimeout.isNegative()) {
            throw new IllegalArgumentException("runTimeout must be positive");
        }
        if (stateStore == null) throw new IllegalArgumentException("stateStore must not be null");
        this.maxActiveRuns = maxActiveRuns;
        this.runTimeout = runTimeout;
        this.stateStore = stateStore;
    }

    /** 进程启动时收敛上一进程遗留的 running 状态。 */
    @PostConstruct
    void markStaleRunsInterrupted() {
        safeState(() -> stateStore.markInterrupted());
    }

    /**
     * 启动一个 owner session 的后台流并返回可订阅的事件 relay。
     * @param request 已完成认证和会话归属确认的请求
     * @param runtime 聊天 runtime
     * @return 可被 HTTP 客户端订阅的事件流
     * @throws ActiveChatRunException 当前会话已有运行中的 Agent
     */
    public Flux<ChatSseEvent> start(ChatRequest request, ChatRuntime runtime) {
        String sessionId = request.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new ActiveChatRunException("an owner-scoped chat session is required");
        }
        ActiveRun run = new ActiveRun();
        synchronized (runs) {
            if (runs.size() >= maxActiveRuns) {
                throw new ActiveChatRunException("chat service is at active run capacity");
            }
            ActiveRun existing = runs.putIfAbsent(sessionId, run);
            if (existing != null) {
                throw new ActiveChatRunException("chat session already has a running agent");
            }
        }
        safeState(() -> stateStore.start(sessionId));
        try {
            run.subscription = runtime.stream(request)
                    .timeout(runTimeout)
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(event -> {
                        safeState(() -> stateStore.appendEvent(sessionId, event.event(), event.data()));
                        if ("tool_start".equals(event.event())) safeState(() -> stateStore.update(sessionId, "running", "tool"));
                        else if ("tool_trace".equals(event.event())) safeState(() -> stateStore.update(sessionId, "running", "tool_result"));
                        else if ("done".equals(event.event())) safeState(() -> stateStore.update(sessionId, "running", "finalizing"));
                    })
                    .subscribe(
                            run.events::tryEmitNext,
                            error -> {
                                run.events.tryEmitError(error);
                                safeState(() -> stateStore.update(sessionId,
                                        error instanceof TimeoutException ? "timed_out" : "failed",
                                        error instanceof TimeoutException ? "run_timeout" : "error"));
                                runs.remove(sessionId, run);
                            },
                            () -> {
                                run.events.tryEmitComplete();
                                safeState(() -> stateStore.update(sessionId, "completed", "done"));
                                runs.remove(sessionId, run);
                            }
                    );
            return run.events.asFlux();
        } catch (RuntimeException error) {
            runs.remove(sessionId, run);
            throw error;
        }
    }

    /**
     * 订阅当前活跃会话的 relay；没有活跃运行时返回空流。
     * @param sessionId owner-scoped session ID
     * @return replay relay
     */
    public Flux<ChatSseEvent> reconnect(String sessionId) {
        ActiveRun run = runs.get(sessionId);
        if (run != null) return run.events.asFlux();
        return durableReconnect(sessionId);
    }

    /**
     * 跨进程轮询持久化事件；只在当前 JVM 没有本地 relay 时启用。
     * 数据库异常不会伪造事件，轮询最多持续一个 run timeout，终态事件到达后立即结束。
     */
    private Flux<ChatSseEvent> durableReconnect(String sessionId) {
        AtomicLong cursor = new AtomicLong(0);
        return Flux.interval(Duration.ZERO, Duration.ofMillis(250))
                .flatMap(tick -> Mono.fromCallable(() -> durableBatch(sessionId, cursor.get()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .concatMap(batch -> {
                    Flux<ChatSseEvent> events = Flux.fromIterable(batch.events())
                            .map(event -> {
                                cursor.set(Math.max(cursor.get(), event.id()));
                                return new ChatSseEvent(event.event(), event.data());
                            });
                    return batch.terminal()
                            ? events.concatWithValues(DURABLE_TERMINAL)
                            : events;
                })
                .takeUntil(event -> DURABLE_TERMINAL.event().equals(event.event()))
                .filter(event -> !DURABLE_TERMINAL.event().equals(event.event()))
                .take(runTimeout);
    }

    private static final ChatSseEvent DURABLE_TERMINAL = new ChatSseEvent(
            "__durable_reconnect_terminal__", Map.of());

    private DurableBatch durableBatch(String sessionId, long cursor) {
        List<ChatRunStateStore.RunEvent> events = stateStore.loadEvents(sessionId, 4096).stream()
                .filter(event -> event.id() > cursor)
                .toList();
        Map<String, Object> state = stateStore.find(sessionId);
        String status = state == null ? "" : String.valueOf(state.getOrDefault("status", ""));
        boolean terminal = Set.of("completed", "failed", "cancelled", "timed_out", "interrupted")
                .contains(status) && events.isEmpty();
        if (events.stream().anyMatch(event -> Set.of("done", "error").contains(event.event()))) {
            terminal = true;
        }
        return new DurableBatch(events, terminal);
    }

    /** @return 当前 session 是否存在仍在执行的 Agent。 */
    public boolean active(String sessionId) {
        return sessionId != null && runs.containsKey(sessionId);
    }

    /** 返回内存 active 与持久 run 状态合并后的诊断视图。 */
    public Map<String, Object> state(String sessionId) {
        Map<String, Object> persisted = stateStore.find(sessionId);
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        if (persisted != null) state.putAll(persisted);
        boolean active = active(sessionId);
        state.put("active", active);
        if (active) {
            state.put("status", "running");
            state.putIfAbsent("phase", "running");
        } else {
            state.putIfAbsent("status", "idle");
            state.putIfAbsent("phase", "idle");
        }
        return Map.copyOf(state);
    }

    /**
     * 主动取消某个会话的运行，并让 runtime 收到订阅取消信号。
     * @param sessionId owner 已确认的会话 ID
     * @return 存在并已取消运行时为 true
     */
    public boolean cancel(String sessionId) {
        ActiveRun run = runs.remove(sessionId);
        if (run == null) return false;
        run.subscription.dispose();
        run.events.tryEmitComplete();
        safeState(() -> stateStore.update(sessionId, "cancelled", "cancelled"));
        return true;
    }

    /** @return 当前运行中的会话数量，供测试和诊断使用。 */
    int size() {
        return runs.size();
    }

    @Override
    public void close() {
        runs.forEach((sessionId, run) -> {
            run.subscription.dispose();
            run.events.tryEmitComplete();
            safeState(() -> stateStore.update(sessionId, "cancelled", "shutdown"));
        });
        runs.clear();
    }

    private void safeState(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // Run state is diagnostic/recovery metadata; a database outage cannot break the live relay.
        }
    }

    /** 当前运行实例的回放 relay 和 runtime subscription。 */
    private static final class ActiveRun {
        private final Sinks.Many<ChatSseEvent> events = Sinks.many().replay().limit(4096);
        private volatile Disposable subscription = () -> { };
    }

    private record DurableBatch(List<ChatRunStateStore.RunEvent> events, boolean terminal) {
    }

    /** 表示同一会话已有一个运行中的聊天 Agent。 */
    public static final class ActiveChatRunException extends RuntimeException {
        public ActiveChatRunException(String message) {
            super(message);
        }
    }
}
