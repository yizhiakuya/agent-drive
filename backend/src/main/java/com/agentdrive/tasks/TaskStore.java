package com.agentdrive.tasks;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 按 owner 持久化异步任务、任务事件和父子任务关系的接口。
 * 活跃任务的去重由数据库约束保证；列表和状态概览面向顶层任务，子任务通过 {@link #childSummary(UUID, UUID)} 汇总，
 * 事件流则按递增 ID 增量读取，避免每次 SSE 订阅回放整张历史表。
 */
public interface TaskStore {
    /**
     * 分页查询用户任务，并按状态、类型和是否包含子任务筛选。
     * 不包含子任务时返回顶层任务列表；包含子任务时由实现按 API 契约嵌入或追加子任务信息。
     *
     * @param userId 任务归属用户的 UUID。
     * @param statuses 要筛选的状态集合；空集合表示不按状态过滤。
     * @param type 要筛选的任务类型；为空时不过滤类型。
     * @param includeChildren 是否同时加载父任务的子任务摘要/记录。
     * @param limit 本页最大任务数。
     * @param offset 本页相对任务结果集的起始偏移量。
     * @return 按创建时间或任务列表契约排序的任务记录。
     */
    List<Map<String, Object>> list(UUID userId, List<String> statuses, String type,
                                    boolean includeChildren, int limit, int offset);

    /**
     * 汇总用户顶层任务的数量和状态，并把子任务进度归并到对应父任务。
     * 该结果用于任务中心概览，不应把每个子任务重复计入顶层任务总数。
     *
     * @param userId 任务归属用户的 UUID。
     * @return 包含顶层任务计数、状态统计和向量/索引汇总的概览 map。
     */
    Map<String, Object> overview(UUID userId);

    /**
     * 使 owner 的任务概览索引统计失效。
     * 文件变更后应调用此方法；不支持缓存的适配器可以保持默认空实现。
     *
     * @param userId 需要重新计算概览的 owner UUID。
     */
    default void invalidateOverview(UUID userId) {
    }

    /**
     * 按 owner 和任务 UUID 读取单条任务，防止通过猜测 UUID 跨用户访问任务。
     *
     * @param userId 任务归属用户的 UUID。
     * @param taskId 要读取的任务 UUID。
     * @return 任务完整记录；任务不存在或不属于该用户时由实现返回空结果或抛出业务异常。
     */
    Map<String, Object> get(UUID userId, UUID taskId);

    /**
     * 查询父任务下的子任务状态和进度摘要。
     * 子任务仍存在时，清理父任务历史不得先删除这些记录；结果用于父任务详情和整体进度展示。
     *
     * @param userId 父任务归属用户的 UUID。
     * @param parentId 父任务 UUID。
     * @return 子任务摘要列表；没有子任务时返回空列表。
     */
    List<Map<String, Object>> childSummary(UUID userId, UUID parentId);

    /**
     * 创建一条 queued 任务，或返回同一活跃 dedupe key 已存在的任务。
     * 新任务会保存 lane、payload、来源和可选父任务，并写入任务事件；重复调用不得再触发副作用。
     *
     * @param userId 任务归属用户的 UUID。
     * @param type Worker 分发的任务类型。
     * @param lane 任务进入的 Worker lane。
     * @param payload 任务处理器所需的结构化参数。
     * @param dedupeKey 活跃任务范围内的稳定去重键。
     * @param origin 产生任务的来源，例如 API 或 outbox。
     * @param parentId 父任务 UUID；顶层任务传 {@code null}。
     * @return 包含任务记录和 {@code created} 标志的结果；重复任务的 created 为 {@code false}。
     */
    EnqueueResult enqueue(UUID userId, String type, String lane, Map<String, Object> payload,
                          String dedupeKey, String origin, UUID parentId);

    /**
     * 请求取消一条属于用户的任务。
     * queued/retry_wait 任务可直接转为 cancelled，running 任务写入 cancel_requested 供 Worker 在下次状态迁移时处理，终态任务保持不变。
     *
     * @param userId 任务归属用户的 UUID。
     * @param taskId 要取消的任务 UUID。
     * @return 取消请求后的任务状态和是否发生状态变化等结果。
     */
    Map<String, Object> cancel(UUID userId, UUID taskId);

    /**
     * 对可重试的失败任务创建下一次执行机会，并清理或更新其租约、尝试次数和状态。
     * 终态任务或达到最大尝试次数的任务不能通过该入口重新排队。
     *
     * @param userId 任务归属用户的 UUID。
     * @param taskId 要重试的任务 UUID。
     * @return 重试后的任务状态和调度信息。
     */
    Map<String, Object> retry(UUID userId, UUID taskId);

    /**
     * 返回用户任务事件表当前的最大事件 ID，供 SSE 客户端建立增量读取起点。
     *
     * @param userId 事件归属用户的 UUID。
     * @return 当前最大事件 ID；没有事件时返回存储约定的初始值。
     */
    long latestEventId(UUID userId);

    /**
     * 增量读取指定事件 ID 之后的任务事件。
     * 查询从 {@code afterId} 之后开始且最多返回 {@code limit} 条，客户端可用返回事件的最大 ID 更新游标。
     *
     * @param userId 事件归属用户的 UUID。
     * @param afterId 已经消费到的事件 ID，不返回该 ID 本身。
     * @param limit 本次最多返回的事件数。
     * @return 按事件 ID 递增排列的任务事件。
     */
    List<Map<String, Object>> events(UUID userId, long afterId, int limit);

    /**
     * 清理 owner 的终态任务历史，同时保留最近一批记录和仍被子任务引用的父任务。
     *
     * <p>默认实现让旧的测试替身和非持久化适配器保持兼容；生产 PostgreSQL 实现会
     * 在同一事务中删除符合保留策略的任务，任务事件由外键级联清理。</p>
     *
     * @param userId 任务归属 owner 的 UUID。
     * @param olderThanDays 只清理早于该天数的终态任务。
     * @param keepRecent 至少保留该 owner 最近的终态任务数量。
     * @return 清理数量摘要；不支持该能力的适配器返回零计数。
     */
    default Map<String, Object> pruneHistory(UUID userId, int olderThanDays, int keepRecent) {
        return Map.of("jobs", 0, "events", 0, "workers", 0);
    }

    /**
     * 任务入队结果，区分本次是否创建了新任务。
     * {@code task} 在新建和命中现有活跃任务时都返回最终任务记录，调用方可据此向客户端返回统一的任务表示。
     *
     * @param task 新建或复用的任务记录。
     * @param created 本次调用插入新任务时为 {@code true}，命中活跃 dedupe key 时为 {@code false}。
     */
    record EnqueueResult(Map<String, Object> task, boolean created) {
    }
}
