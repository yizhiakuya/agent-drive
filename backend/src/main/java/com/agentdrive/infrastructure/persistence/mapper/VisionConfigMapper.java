package com.agentdrive.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 读写 owner 视觉模型配置 JSON 的 MyBatis 端口。 */
@Mapper
public interface VisionConfigMapper {
    /**
     * 读取 owner 的视觉配置 JSON。
     * @param userId 配置所属 owner UUID 字符串。
     * @return JSON 文本；未配置时为空。
     */
    String select(@Param("userId") String userId);

    /**
     * 覆盖 owner 的视觉配置 JSON。
     * @param userId 配置所属 owner UUID 字符串。
     * @param value 已序列化且不含明文 key 的 JSON。
     * @return 受影响行数。
     */
    int upsert(@Param("userId") String userId, @Param("value") String value);
}
