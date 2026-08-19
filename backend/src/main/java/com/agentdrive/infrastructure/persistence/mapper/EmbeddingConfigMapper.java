package com.agentdrive.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 读写 owner embedding 配置 JSON 的 MyBatis 端口。 */
@Mapper
public interface EmbeddingConfigMapper {
    /** 读取 owner 的 embedding 配置 JSON。 @param userId 配置所属 owner UUID。 @return JSON 文本；未配置时为空。 */
    String select(@Param("userId") String userId);

    /** 覆盖 owner 的 embedding 配置 JSON。 @param userId 配置所属 owner UUID。 @param value 已序列化的配置 JSON。 @return 更新行数。 */
    int upsert(@Param("userId") String userId, @Param("value") String value);
}
