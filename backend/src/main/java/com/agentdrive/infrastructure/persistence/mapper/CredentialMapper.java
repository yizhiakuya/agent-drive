package com.agentdrive.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

/** 按凭据 hash 解析 session 或设备 owner 的 MyBatis SQL 端口。 */
@Mapper
public interface CredentialMapper {
    /** 查询未撤销 session 的 owner。 @param credentialHash session token SHA-256。 @return 含 user_id 的认证行；未命中时为空。 */
    Map<String, Object> selectSessionOwner(@Param("credentialHash") String credentialHash);

    /** 查询未撤销设备令牌的 owner。 @param credentialHash 设备 token SHA-256。 @return 含 user_id 的认证行；未命中时为空。 */
    Map<String, Object> selectDeviceOwner(@Param("credentialHash") String credentialHash);
}
