package com.agentdrive.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** owner-scoped 会话、消息和摘要字段的 MyBatis SQL 端口。 */
@Mapper
public interface ConversationSessionMapper {
    /** 按 owner 和 session ID 查询会话归属。 @param userId 请求方 owner UUID。 @param sessionId 会话 UUID。 @return 会话行；不属于 owner 时为空。 */
    Map<String, Object> selectOwned(@Param("userId") String userId,
                                    @Param("sessionId") String sessionId);

    /** 创建 owner 会话。 @param userId 会话所属 owner UUID。 @return 新会话 UUID 文本。 */
    String insertSession(@Param("userId") String userId);

    /** 列出 owner 的会话元数据。 @param userId 请求方 owner UUID。 @return 会话行列表。 */
    List<Map<String, Object>> selectOwnedList(@Param("userId") String userId);

    /** 查询 owner 会话详情。 @param userId 请求方 owner UUID。 @param sessionId 会话 UUID。 @return 详情行；不存在时为空。 */
    Map<String, Object> selectOwnedDetails(@Param("userId") String userId,
                                            @Param("sessionId") String sessionId);

    /** 读取 owner 会话消息和工具扩展列。 @param userId 请求方 owner UUID。 @param sessionId 会话 UUID。 @return 按时间顺序的消息行。 */
    List<Map<String, Object>> selectMessages(@Param("userId") String userId,
                                             @Param("sessionId") String sessionId);

    /** 删除 owner 会话及其消息。 @param userId 会话所属 owner UUID。 @param sessionId 会话 UUID。 @return 删除行数。 */
    int deleteOwned(@Param("userId") String userId, @Param("sessionId") String sessionId);

    /** 更新会话摘要和标题。 @param userId 会话所属 owner UUID。 @param sessionId 会话 UUID。 @param summary 新摘要。 @param title 新标题。 @return 更新行数。 */
    int updateSummary(@Param("userId") String userId, @Param("sessionId") String sessionId,
                      @Param("summary") String summary, @Param("title") String title);
}
