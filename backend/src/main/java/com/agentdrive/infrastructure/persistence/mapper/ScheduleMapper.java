package com.agentdrive.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * owner 级持久化任务调度配置的 MyBatis 映射接口。
 *
 * <p>调度配置以 {@code (user_id, name)} 区分 owner 和名称；查询到期调度时由当前数据库事务
 * 加锁并跳过已被其他 worker 锁定的行，派发后再更新下一次运行时间和最近任务 ID。
 */
@Mapper
public interface ScheduleMapper {
    /**
     * 查询 owner 的全部调度配置。
     *
     * <p>SQL 按 owner 精确过滤并按名称升序返回完整调度字段，包括 cron、调度参数、任务类型、
     * lane、JSON payload、启用状态、重试上限、时区、下一次运行时间和最近任务 ID。
     *
     * @param userId 调度配置所属 owner 的 UUID 字符串
     * @return 按名称升序排列的调度字段映射列表；没有配置时返回空列表
     */
    List<Map<String, Object>> selectSchedules(@Param("userId") String userId);

    /**
     * 插入或更新 owner 指定名称的任务调度配置。
     *
     * <p>首次插入保存全部调度字段，并使用调用方已校验计算的 {@code next_run_at}；
     * 同一 owner 和名称冲突时更新 cron、调度参数、任务参数、启用状态、优先级、最大尝试次数和
     * 时区，同时替换 {@code next_run_at} 并清除旧错误。语句返回更新后的完整调度记录。
     *
     * @param userId 调度配置所属 owner 的 UUID 字符串
     * @param name owner 内唯一的调度名称
     * @param cron cron 表达式
     * @param scheduleKind 调度值的类型
     * @param scheduleValue 调度类型对应的值
     * @param taskType 到期后创建的任务类型
     * @param lane 到期任务使用的执行 lane
     * @param payload 到期任务的 JSON 正文
     * @param enabled 是否允许该调度被选为到期任务
     * @param priority 到期任务的优先级
     * @param maxAttempts 到期任务允许的最大尝试次数
     * @param timezone 解释 cron 的时区
     * @param nextRunAt 已按表达式计算的首次运行 Unix 秒
     * @return 插入或更新后的调度字段映射，包含数据库生成的 ID 和时间字段
     */
    Map<String, Object> upsert(@Param("userId") String userId,
                               @Param("name") String name,
                               @Param("cron") String cron,
                               @Param("scheduleKind") String scheduleKind,
                               @Param("scheduleValue") String scheduleValue,
                               @Param("taskType") String taskType,
                               @Param("lane") String lane,
                               @Param("payload") String payload,
                               @Param("enabled") boolean enabled,
                               @Param("priority") int priority,
                               @Param("maxAttempts") int maxAttempts,
                               @Param("timezone") String timezone,
                               @Param("nextRunAt") double nextRunAt);

    /**
     * 删除 owner 指定名称的调度配置。
     *
     * @param userId 调度配置所属 owner 的 UUID 字符串
     * @param name 要删除的调度名称
     * @return 实际删除的调度数；owner 或名称不匹配时为 {@code 0}
     */
    int delete(@Param("userId") String userId, @Param("name") String name);

    /**
     * 锁定并查询 owner 当前已到期且启用的调度配置。
     *
     * <p>SQL 要求 {@code enabled = true}、{@code next_run_at} 非空且不晚于当前时间，按下一次
     * 运行时间升序取前 {@code limit} 条，并使用 {@code FOR UPDATE SKIP LOCKED} 跳过其他事务已锁定
     * 的调度行。该查询本身不更新调度状态。
     *
     * @param userId 调度配置所属 owner 的 UUID 字符串
     * @param limit 本次最多锁定并返回的调度数
     * @return 当前事务锁定的到期调度字段映射列表；没有可领取调度时返回空列表
     */
    List<Map<String, Object>> selectDue(@Param("userId") String userId, @Param("limit") int limit);

    /**
     * 锁定并查询所有 owner 当前已到期且启用的调度配置。
     *
     * <p>SQL 不带 owner 条件，仅要求启用、下一次运行时间非空且不晚于当前时间，按下一次运行
     * 时间升序取前 {@code limit} 条，并使用 {@code FOR UPDATE SKIP LOCKED} 跳过已锁定行。返回
     * 映射中的 {@code owner_user_id} 标识每条调度的 owner。
     *
     * @param limit 本次最多锁定并返回的跨 owner 调度数
     * @return 当前事务锁定的到期调度字段映射列表；没有可领取调度时返回空列表
     */
    List<Map<String, Object>> selectDueAll(@Param("limit") int limit);

    /**
     * 更新 owner 调度配置的下一次运行时间和最近派发任务 ID。
     *
     * <p>SQL 按 owner 和调度 ID 精确匹配，将 Unix epoch 秒数转换为数据库时间写入
     * {@code next_run_at}，保存任务 ID，并刷新 {@code updated_at}。该语句不额外检查调度是否到期
     * 或启用。
     *
     * @param userId 调度配置所属 owner 的 UUID 字符串
     * @param scheduleId 调度配置 UUID 字符串
     * @param nextRunAt 下一次运行时间的 Unix epoch 秒数
     * @param taskId 本次派发创建的任务 UUID 字符串
     * @return 实际更新的调度数；owner 或调度 ID 不匹配时为 {@code 0}
     */
    int markDispatched(@Param("userId") String userId, @Param("scheduleId") String scheduleId,
                       @Param("nextRunAt") double nextRunAt, @Param("taskId") String taskId);

    /** 写入立即运行的最近任务和执行时间，不改变计划下一次自动运行时间。 */
    int markManualRun(@Param("userId") String userId, @Param("name") String name,
                      @Param("taskId") String taskId);

    /** 禁用无法解析的历史计划并保留稳定错误说明，避免其反复占据 Worker 队首。 */
    int disableInvalid(@Param("userId") String userId, @Param("scheduleId") String scheduleId,
                       @Param("error") String error);
}
