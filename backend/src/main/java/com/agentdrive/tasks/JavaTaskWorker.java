package com.agentdrive.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 独立 Java Worker 进程中的周期调度入口。
 * 单线程每秒依次派发到期 schedule、消费文件变更 outbox，再从任务库领取一条任务；任务租约和最终状态由持久化 store 管理，
 * 因此进程重启后可由其他 Worker 回收过期租约继续执行。
 */
@Component
@Profile({"java-files", "java-auth", "java-chat"})
@ConditionalOnProperty(name = "app.mode", havingValue = "worker")
public final class JavaTaskWorker implements ApplicationRunner, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(JavaTaskWorker.class);
    private final ScheduleStore schedules;
    private final IndexOutboxConsumer outboxConsumer;
    private final IndexTaskHandler handler;
    private final TaskWorkerStore workers;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "agent-drive-java-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "agent-drive-java-worker-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private final String workerId = "java-worker-" + UUID.randomUUID();

    /**
     * 创建 Worker，并保存 schedule 派发器、outbox 消费器和任务处理器。
     * 调度器使用单线程 daemon executor，避免同一进程内同时推进多个任务循环。
     *
     * @param schedules 读取到期 schedule 并生成任务的存储服务。
     * @param outboxConsumer 把文件变更事件转换为索引任务的消费者。
     * @param handler 领取并执行具体任务的处理器。
     * @param workers 写入进程心跳并推进任务租约的存储服务。
     */
    public JavaTaskWorker(ScheduleStore schedules, IndexOutboxConsumer outboxConsumer, IndexTaskHandler handler,
                          TaskWorkerStore workers) {
        this.schedules = schedules;
        this.outboxConsumer = outboxConsumer;
        this.handler = handler;
        this.workers = workers;
    }

    /**
     * 注册固定延迟调度；Spring 启动完成后立即执行第一次 tick，之后每次 tick 结束一秒再开始下一轮。
     * ApplicationRunner 参数不参与业务逻辑，因为 Worker 的配置来自 Spring profile 和环境文件。
     *
     * @param args Spring 启动参数，当前实现不读取。
     */
    @Override
    public void run(ApplicationArguments args) {
        heartbeatExecutor.scheduleWithFixedDelay(this::heartbeat, 0, 2, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
        LOGGER.info("java task worker started worker_id={}", workerId);
    }

    /**
     * 执行一轮后台工作：最多派发 20 个到期 schedule、消费 20 条 outbox，再按 lane 优先级执行一条任务。
     * schedule、outbox 和 task 各自隔离 RuntimeException；前一阶段失败不跳过后续阶段，
     * 具体已领取任务的业务异常由 {@link IndexTaskHandler} 写入任务失败状态。
     */
    void tick() {
        runStage("schedule", () -> schedules.dispatchDueAll(20));
        runStage("outbox", () -> outboxConsumer.consumeOnce(20));
        runStage("task", () -> handler.runOnce(workerId));
    }

    /** 单独隔离每个轮询阶段，确保前一阶段失败不会跳过后续阶段。 */
    private void runStage(String stage, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException error) {
            LOGGER.warn("java task worker stage failed worker_id={} stage={} error_type={}",
                    workerId, stage, error.getClass().getSimpleName());
        }
    }

    /**
     * 独立刷新进程在线时间，避免长任务阻塞工作循环时被 API 误判为掉线。
     * 心跳失败只影响在线展示，不中断任务线程；下一次周期会继续尝试恢复登记。
     */
    private void heartbeat() {
        try {
            workers.touchWorker(workerId);
        } catch (RuntimeException error) {
            LOGGER.warn("java task worker heartbeat failed worker_id={} error_type={}",
                    workerId, error.getClass().getSimpleName());
        }
    }

    /**
     * 立即停止周期调度线程。
     * 已在 store 中持有的任务租约由任务状态恢复机制处理；该方法本身不伪造成功或失败结果。
     */
    @Override
    public void close() {
        heartbeatExecutor.shutdownNow();
        executor.shutdownNow();
        try {
            workers.removeWorker(workerId);
        } catch (RuntimeException error) {
            LOGGER.warn("java task worker unregister failed worker_id={} error_type={}",
                    workerId, error.getClass().getSimpleName());
        }
        LOGGER.info("java task worker stopped worker_id={}", workerId);
    }
}
