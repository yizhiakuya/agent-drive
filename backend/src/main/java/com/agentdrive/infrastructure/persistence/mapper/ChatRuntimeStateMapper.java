package com.agentdrive.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;
import java.util.List;

/** 访问聊天运行时 replay、pending confirmation、消息、trace 和 nonce 表的 MyBatis 端口。 */
@Mapper
public interface ChatRuntimeStateMapper {
    /** 按会话读取已经成功执行过的 read_skill 名称。 @param sessionId 会话 UUID。 @return 去重 Skill 名称。 */
    List<String> selectLoadedSkillNames(@Param("sessionId") String sessionId);

    /** 按会话、工具和精确参数 JSON 查询可重放结果。 @param sessionId 会话 UUID 文本。 @param tool 工具名。 @param arguments 参数 JSON。 @return replay 行；无匹配时为 {@code null}。 */
    Map<String, Object> selectToolReplay(@Param("sessionId") String sessionId,
                                          @Param("tool") String tool,
                                          @Param("arguments") String arguments);

    /** 保存工具重放结果。 @param sessionId 会话 UUID。 @param tool 工具名。 @param arguments 精确参数 JSON。 @param output 原始输出。 @param parsed 解析结果 JSON。 @return 插入行数。 */
    int insertToolReplay(@Param("sessionId") String sessionId,
                         @Param("tool") String tool,
                         @Param("arguments") String arguments,
                         @Param("output") String output,
                         @Param("parsed") String parsed);

    /** 删除会话中所有可重放结果，使 mutation 后的读取不会命中旧快照。 */
    int deleteToolReplays(@Param("sessionId") String sessionId);

    /** 读取 owner 会话的模型可见 user/assistant 历史，按时间升序返回。 */
    List<Map<String, Object>> selectModelHistory(@Param("userId") String userId,
                                                 @Param("sessionId") String sessionId,
                                                 @Param("limit") int limit);

    /** 插入一条会话消息或工具 trace。 @param sessionId 会话 UUID。 @param role 消息角色。 @param content 正文/工具输出。 @param reasoning 独立 reasoning。 @param tool 工具名。 @param arguments 参数 JSON。 @param parsed 解析结果 JSON。 @return 插入行数。 */
    int insertMessage(@Param("sessionId") String sessionId,
                      @Param("role") String role,
                      @Param("content") String content,
                      @Param("reasoning") String reasoning,
                      @Param("tool") String tool,
                      @Param("arguments") String arguments,
                      @Param("parsed") String parsed);

    /** 当同来源最新快照不同时插入上下文。 @param sessionId 会话 UUID。 @param source 来源名称。 @param kind 上下文类型。 @param content 完整文本。 @return 插入行数。 */
    int insertContextIfChanged(@Param("sessionId") String sessionId,
                               @Param("source") String source,
                               @Param("kind") String kind,
                               @Param("content") String content);

    /** 覆盖会话 last_trace。 @param sessionId 会话 UUID。 @param trace 已脱敏的 trace JSON。 @return 更新行数。 */
    int updateLastTrace(@Param("sessionId") String sessionId, @Param("trace") String trace);

    /** 覆盖会话上下文窗口用量。 @param sessionId 会话 UUID。 @param usage 用量 JSON。 @return 更新行数。 */
    int updateContextUsage(@Param("sessionId") String sessionId, @Param("usage") String usage);

    /** 创建新的 Agent run 状态。 */
    int startRun(@Param("sessionId") String sessionId, @Param("state") String state);

    /** 更新 Agent run 阶段或终态。 */
    int updateRun(@Param("sessionId") String sessionId, @Param("status") String status,
                  @Param("phase") String phase);

    /** 读取最近 Agent run 状态。 */
    Map<String, Object> selectRun(@Param("sessionId") String sessionId);

    /** 把进程重启前遗留的 running run 标成 interrupted。 */
    int interruptRunningRuns();

    /** 持久化一个可重连的 SSE run 事件。 */
    int insertRunEvent(@Param("sessionId") String sessionId, @Param("event") String event,
                       @Param("data") String data);

    /** 读取 run SSE 事件历史。 */
    List<Map<String, Object>> selectRunEvents(@Param("sessionId") String sessionId,
                                              @Param("limit") int limit);

    /** 读取会话 pending confirmation JSON。 @param sessionId 会话 UUID。 @return pending JSON；不存在时为 {@code null}。 */
    String selectPending(@Param("sessionId") String sessionId);

    /** 写入会话 pending confirmation。 @param sessionId 会话 UUID。 @param pending 原文 pending JSON。 @return 更新行数。 */
    int updatePending(@Param("sessionId") String sessionId, @Param("pending") String pending);

    /** 清除会话 pending confirmation。 @param sessionId 会话 UUID。 @return 更新行数。 */
    int clearPending(@Param("sessionId") String sessionId);

    /** 原子消费确认 nonce，阻止同一确认请求重放。 @param sessionId 会话 UUID。 @param nonce 一次性 nonce。 @return 消费行数。 */
    int consumeNonce(@Param("sessionId") String sessionId, @Param("nonce") String nonce);
}
