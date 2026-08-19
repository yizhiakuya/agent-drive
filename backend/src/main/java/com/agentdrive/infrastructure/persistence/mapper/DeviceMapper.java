package com.agentdrive.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** owner-scoped 设备注册表 metadata、同步状态和撤销状态的 MyBatis 端口。 */
@Mapper
public interface DeviceMapper {
    /** 列出 owner 的活动设备。 @param userId 设备所属 owner UUID。 @return 未撤销设备行。 */
    List<Map<String, Object>> selectActive(@Param("userId") String userId);

    /** upsert 设备 metadata 和同步状态。 @param userId owner UUID。 @param deviceId 客户端设备 ID。 @param name 显示名称。 @param model 设备型号。 @param platform 平台。 @param appVersion 客户端版本。 @param syncState JSON 同步状态。 @return 更新后的设备行。 */
    Map<String, Object> upsertMetadata(@Param("userId") String userId,
                                       @Param("deviceId") String deviceId,
                                       @Param("name") String name,
                                       @Param("model") String model,
                                       @Param("platform") String platform,
                                       @Param("appVersion") String appVersion,
                                       @Param("syncState") String syncState);

    /** 撤销 owner 下的指定设备。 @param userId owner UUID。 @param deviceId 客户端设备 ID。 @return 更新行数。 */
    int revoke(@Param("userId") String userId, @Param("deviceId") String deviceId);
}
