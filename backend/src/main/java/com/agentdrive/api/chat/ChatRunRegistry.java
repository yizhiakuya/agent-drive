package com.agentdrive.api.chat;

import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 将聊天 runtime 订阅与单个 HTTP/SSE 客户端解耦。
 * 客户端断开只会卸载 relay subscriber；runtime 继续运行并把结果写入 transcript。
 */
@Component
public final class ChatRunRegistry implements AutoCloseable {
    private final Map<String, ActiveRun> runs = new ConcurrentHashMap<>();

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
            return runtime.stream(request);
        }
        ActiveRun run = new ActiveRun();
        ActiveRun existing = runs.putIfAbsent(sessionId, run);
        if (existing != null) {
            throw new ActiveChatRunException("chat session already has a running agent");
        }
        try {
            run.subscription = runtime.stream(request)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            run.events::tryEmitNext,
                            error -> {
                                run.events.tryEmitError(error);
                                runs.remove(sessionId, run);
                            },
                            () -> {
                                run.events.tryEmitComplete();
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
        return run == null ? Flux.empty() : run.events.asFlux();
    }

    /** @return 当前 session 是否存在仍在执行的 Agent。 */
    public boolean active(String sessionId) {
        return sessionId != null && runs.containsKey(sessionId);
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
        });
        runs.clear();
    }

    /** 当前运行实例的回放 relay 和 runtime subscription。 */
    private static final class ActiveRun {
        private final Sinks.Many<ChatSseEvent> events = Sinks.many().replay().limit(4096);
        private volatile Disposable subscription = () -> { };
    }

    /** 表示同一会话已有一个运行中的聊天 Agent。 */
    public static final class ActiveChatRunException extends RuntimeException {
        public ActiveChatRunException(String message) {
            super(message);
        }
    }
}
