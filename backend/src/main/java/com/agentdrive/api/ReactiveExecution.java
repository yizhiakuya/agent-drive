package com.agentdrive.api;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 统一把 Controller 边界的同步工作移出 WebFlux event-loop。
 */
public final class ReactiveExecution {
    private ReactiveExecution() {
    }

    /**
     * 将同步操作延迟到订阅时执行，并切换到共享 bounded-elastic 调度器。
     *
     * @param operation 待执行的同步操作
     * @param <T> 返回值类型
     * @return 延迟执行的异步结果
     */
    public static <T> Mono<T> blocking(Callable<T> operation) {
        return Mono.fromCallable(Objects.requireNonNull(operation, "operation"))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 在 bounded-elastic 上创建并订阅可能执行同步工作的 Publisher。
     *
     * @param publisherFactory 延迟创建 Publisher 的工厂
     * @param <T> 返回值类型
     * @return 在阻塞调度器执行源创建和订阅的异步结果
     */
    public static <T> Mono<T> onBlockingScheduler(Supplier<Mono<T>> publisherFactory) {
        Supplier<Mono<T>> factory = Objects.requireNonNull(publisherFactory, "publisherFactory");
        return Mono.defer(() -> Objects.requireNonNull(factory.get(), "publisher"))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
