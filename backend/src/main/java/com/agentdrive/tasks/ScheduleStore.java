package com.agentdrive.tasks;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 按用户持久化自动任务调度规则的接口。
 * schedule 记录描述何时、以什么时区、按哪个 lane 生成何种任务；Worker 通过 dispatch 方法读取到期记录并把它们交给任务库，
 * 因而调度规则本身不直接执行任务逻辑。
 */
public interface ScheduleStore {
    /**
     * 查询用户配置的全部 schedule 记录。
     *
     * @param userId schedule 归属用户的 UUID。
     * @return schedule 定义及启用状态、下次执行时间等持久化字段。
     */
    List<Map<String, Object>> list(UUID userId);

    /**
     * 按用户和名称创建或更新一条 schedule 规则。
     * {@code scheduleKind/value} 描述调度表达式，{@code taskType/lane/payload} 描述到期后入队的任务；更新后应重新计算下次执行时间。
     *
     * @param userId schedule 归属用户的 UUID。
     * @param name 用户范围内稳定唯一的 schedule 名称。
     * @param cron cron 表达式；具体允许的格式由调度实现校验。
     * @param scheduleKind 调度种类，例如 cron 或 interval。
     * @param scheduleValue 与种类对应的表达式或间隔值。
     * @param taskType 到期时创建的任务类型。
     * @param lane 任务进入的 Worker lane。
     * @param payload 任务执行所需的结构化参数。
     * @param enabled 是否允许该规则产生任务。
     * @param priority 生成任务的优先级。
     * @param maxAttempts 生成任务允许的最大执行尝试次数。
     * @param timezone 解释 cron/时间值的 IANA 时区。
     * @return 持久化后的 schedule 记录。
     */
    Map<String, Object> upsert(UUID userId, String name, String cron, String scheduleKind,
                                String scheduleValue, String taskType, String lane,
                                Map<String, Object> payload, boolean enabled, int priority,
                                int maxAttempts, String timezone);

    /**
     * 删除用户指定名称的 schedule 规则。
     *
     * @param userId schedule 归属用户的 UUID。
     * @param name 要删除的用户范围内 schedule 名称。
     * @return 删除到记录时为 {@code true}，名称不存在时为 {@code false}。
     */
    boolean delete(UUID userId, String name);

    /**
     * 查找一个用户已经到期的 enabled schedule，并将每条规则转换为待入队任务，同时推进其下次执行时间。
     * 该方法只负责调度派发，不执行任务 payload。
     *
     * @param userId schedule 归属用户的 UUID。
     * @param limit 本次最多处理的到期规则数。
     * @return 本轮生成或已存在的任务记录。
     */
    List<Map<String, Object>> dispatchDue(UUID userId, int limit);

    /**
     * 在后台 Worker 范围内扫描所有用户的到期 schedule 并派发任务。
     *
     * @param limit 本轮最多派发的规则数。
     * @return 本轮生成或已存在的任务记录。
     */
    List<Map<String, Object>> dispatchDueAll(int limit);
}
