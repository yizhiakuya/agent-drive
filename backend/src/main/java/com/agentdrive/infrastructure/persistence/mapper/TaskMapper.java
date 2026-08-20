package com.agentdrive.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 任务与任务事件的 PostgreSQL 持久化 Mapper。
 * 任务查询、创建、取消和重试 SQL 按用户 UUID 隔离；事件游标查询也只返回该用户任务的事件。
 * 本接口只负责 MyBatis SQL 映射，不负责任务状态机编排、租约执行或权限决策。
 */
@Mapper
public interface TaskMapper {
    /**
     * 分页读取用户的任务列表。
     * SQL 按状态列表和任务类型可选过滤；{@code includeChildren} 为 {@code false} 时只读取顶层任务，
     * 结果按优先级降序、创建时间降序排列，并返回租约、取消标记、进度和各时间戳等任务列。
     * @param userId 任务所属用户的 UUID 字符串。
     * @param statuses 可选状态过滤；为 {@code null} 或空列表时不添加状态条件。
     * @param type 可选任务类型；为空时不添加 {@code kind} 条件。
     * @param includeChildren 是否同时返回 {@code parent_id} 非空的子任务。
     * @param limit 本次最多处理的条目数量；任务列表 API 允许传入 201 作为 has_more 探测值。
     * @param offset 分页读取的起始偏移量。
     * @return 按 SQL 投影返回的任务行；没有匹配项时返回空列表。
     */
    List<Map<String, Object>> selectTasks(@Param("userId") String userId,
                                          @Param("statuses") List<String> statuses,
                                          @Param("type") String type,
                                          @Param("includeChildren") boolean includeChildren,
                                          @Param("limit") int limit,
                                          @Param("offset") int offset);

    /**
     * 汇总用户顶层任务的状态数量。
     * SQL 固定过滤 {@code parent_id IS NULL}，按 {@code status} 分组并按状态名排序，因此子任务不会重复计入任务中心总数。
     * @param userId 任务所属用户的 UUID 字符串。
     * @return 每个状态对应一行 {@code status/count}；没有任务时返回空列表。
     */
    List<Map<String, Object>> selectStatusCounts(@Param("userId") String userId);

    /**
     * 统计最近 10 秒内仍有心跳的 Java Worker。
     * 在线窗口略大于 Worker 的 2 秒心跳周期，用于覆盖一次短暂数据库抖动而不把长时间掉线误报在线。
     * @return 当前在线 Worker 数量；没有在线 Worker 时返回 0。
     */
    int selectOnlineWorkerCount();

    /**
     * 读取一个属于用户的任务及其持久化状态。
     * SQL 同时匹配 {@code user_id} 和任务 UUID，避免只凭任务 ID 跨用户读取；未找到时不会生成占位行。
     * @param userId 任务所属用户的 UUID 字符串。
     * @param taskId 要读取的任务 UUID 字符串。
     * @return 任务列映射；任务不存在或不属于该用户时返回 {@code null}。
     */
    Map<String, Object> selectTask(@Param("userId") String userId, @Param("taskId") String taskId);

    /**
     * 读取指定顶层任务的直接子任务。
     * SQL 按用户和 {@code parent_id} 限定，并按创建时间升序返回，供父任务详情汇总子任务状态和进度。
     * @param userId 父任务所属用户的 UUID 字符串。
     * @param taskId 父任务 UUID 字符串。
     * @return 直接子任务的任务列映射；没有子任务时返回空列表。
     */
    List<Map<String, Object>> selectChildSummary(@Param("userId") String userId,
                                                  @Param("taskId") String taskId);

    /**
     * 创建一个初始状态为 {@code queued} 的任务。
     * SQL 将 payload 转为 {@code jsonb}、空 origin 默认成 {@code api}，可选关联父任务，并把 {@code available_at} 设为当前时间。
     * 活跃状态（{@code queued}、{@code running}、{@code retry_wait}、{@code cancelling}）的相同去重键由部分唯一约束去重；
     * 冲突时 {@code DO NOTHING}，不会覆盖已有任务。
     * @param userId 任务所属用户的 UUID 字符串。
     * @param parentId 可选父任务 UUID 字符串。
     * @param type 任务类型，对应数据库的 {@code kind}。
     * @param lane Worker 领取任务时使用的执行 lane。
     * @param dedupeKey 活跃任务去重键，可为 {@code null}。
     * @param payload 任务 JSON 文本，将转换为 {@code jsonb}。
     * @param origin 创建来源；为空时由 SQL 写入 {@code api}。
     * @param priority 非负任务调度优先级。
     * @param maxAttempts 至少为 1 的最大执行次数。
     * @return 成功插入并由 {@code RETURNING} 投影出的任务行；活跃去重冲突时返回 {@code null}。
     */
    Map<String, Object> insertTask(@Param("userId") String userId,
                                   @Param("parentId") String parentId,
                                   @Param("type") String type,
                                   @Param("lane") String lane,
                                   @Param("dedupeKey") String dedupeKey,
                                   @Param("payload") String payload,
                                   @Param("origin") String origin,
                                   @Param("priority") int priority,
                                   @Param("maxAttempts") int maxAttempts);

    /**
     * 查找用户仍处于活跃状态的同去重键任务。
     * SQL 只匹配 {@code queued}、{@code running}、{@code retry_wait}、{@code cancelling}，按创建时间倒序取一行。
     * @param userId 任务所属用户的 UUID 字符串。
     * @param dedupeKey 要查询的去重键。
     * @return 最新的活跃任务行；没有匹配项时返回 {@code null}。
     */
    Map<String, Object> selectActiveByDedupe(@Param("userId") String userId,
                                             @Param("dedupeKey") String dedupeKey);

    /**
     * 请求取消一个属于用户的任务，并按当前状态推进取消状态机。
     * {@code queued} 和 {@code retry_wait} 直接变为 {@code cancelled} 并写入完成时间；
     * {@code running} 变为 {@code cancelling}，等待 Worker 结束；已处于 {@code cancelling}
     * 或终态的任务不更新，避免重复刷新时间戳和写入虚假事件。
     * @param userId 任务所属用户的 UUID 字符串。
     * @param taskId 要取消的任务 UUID 字符串。
     * @return SQL 实际更新的行数；任务不存在、不属于该用户或已经不需迁移时为 {@code 0}。
     */
    int cancelTask(@Param("userId") String userId, @Param("taskId") String taskId);

    /**
     * 将失败或已取消的任务重置为可重新领取的 {@code queued} 状态。
     * SQL 仅更新 {@code failed}、{@code cancelled}，并清除错误、结果、租约、时间戳和取消标记，重置尝试次数与进度，
     * 将 {@code available_at} 设为当前时间。
     * @param userId 任务所属用户的 UUID 字符串。
     * @param taskId 要重试的任务 UUID 字符串。
     * @return SQL 实际更新的行数；状态不允许重试或任务不属于该用户时为 {@code 0}。
     */
    int retryTask(@Param("userId") String userId, @Param("taskId") String taskId);

    /**
     * 为任务追加一条事件记录。
     * SQL 将任务 UUID、事件类型和 JSON payload 插入 {@code task_events}；事件 ID 和创建时间由数据库生成。
     * 该 SQL 不带 {@code user_id} 条件，调用方必须在进入 Mapper 前完成任务归属校验。
     * @param taskId 事件所属任务的 UUID 字符串。
     * @param type 事件类型，例如状态或进度事件名。
     * @param payload 事件 JSON 文本，将转换为 {@code jsonb}。
     * @return SQL 实际插入的行数。
     */
    int insertEvent(@Param("taskId") String taskId,
                    @Param("type") String type,
                    @Param("payload") String payload);

    /**
     * 读取用户任务事件流当前的最大事件 ID。
     * SQL 通过 {@code task_events.task_id = tasks.id} 连接并按 {@code tasks.user_id} 限定，作为无游标订阅的起始尾部。
     * @param userId 任务所属用户的 UUID 字符串。
     * @return 最大事件 ID；用户没有事件时为 {@code null}。
     */
    Long latestEventId(@Param("userId") String userId);

    /**
     * 按递增事件 ID 读取用户任务事件游标之后的事件。
     * SQL 连接任务表做 owner 过滤，仅返回 {@code e.id > afterId} 的事件，按 ID 升序并受 limit 限制，避免回放用户无关或旧事件。
     * @param userId 任务所属用户的 UUID 字符串。
     * @param afterId 已消费的事件 ID；只返回更大的 ID。
     * @param limit 本次最多处理的条目数量。
     * @return 事件 ID、任务 ID、类型、JSON payload 和创建时间的映射；没有新事件时返回空列表。
     */
    List<Map<String, Object>> selectEvents(@Param("userId") String userId,
                                           @Param("afterId") long afterId,
                                           @Param("limit") int limit);

    /**
     * 按 owner 和历史保留策略删除终态任务。
     *
     * <p>SQL 先排除最近任务，再递归保护仍有未删除子任务的候选父任务，最后删除可安全
     * 回收的任务；任务事件依赖外键级联删除。</p>
     *
     * @param userId 任务归属 owner 的 UUID 字符串。
     * @param cutoffEpoch 终态任务完成/更新时间的 Unix epoch 截止秒数。
     * @param keepRecent 至少保留的最近终态任务数量。
     * @return 实际删除的任务数量。
     */
    int pruneHistory(@Param("userId") String userId,
                     @Param("cutoffEpoch") double cutoffEpoch,
                     @Param("keepRecent") int keepRecent);

    /**
     * 清理 owner 当前所有已结束任务，并递归清理其中没有活动后代的任务树。
     * 活动任务及其祖先会被保护；返回值包含任务和子任务记录总数。
     * @param userId 任务归属 owner 的 UUID 字符串。
     * @return 实际删除的任务记录数量。
     */
    int clearTerminal(@Param("userId") String userId);

    /**
     * 删除指定已结束任务及其已结束后代；若存在活动后代则整组不删除。
     * @param userId 任务归属 owner 的 UUID 字符串。
     * @param taskId 任务 UUID 字符串。
     * @return 实际删除的任务记录数量。
     */
    int deleteTask(@Param("userId") String userId, @Param("taskId") String taskId);
}
