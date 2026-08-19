package com.agentdrive.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.Map;

/** 访问 owner 账户、session、设备令牌和一次性配对码表的 MyBatis SQL 端口。 */
@Mapper
public interface AuthAccountMapper {
    /** 查询唯一 owner 账户的数据库列。 @return owner 行；不存在时为 {@code null}。 */
    Map<String, Object> selectOwner();

    /** 查询 owner 的密码哈希。 @return 密码哈希；未初始化时为 {@code null}。 */
    String selectOwnerPasswordHash();

    /** 插入 owner 账户。 @param passwordHash 已哈希的密码。 @return 数据库生成的 owner UUID 文本。 */
    String insertOwner(@Param("passwordHash") String passwordHash);

    /** 插入带过期时间的 session credential。 @param userId session 所属 owner UUID。 @param credentialHash token 哈希。 @param expiresAt 失效时间。 */
    void insertSession(@Param("userId") String userId,
                       @Param("credentialHash") String credentialHash,
                       @Param("expiresAt") Instant expiresAt);

    /** 撤销匹配 hash 的 session。 @param credentialHash session token 哈希。 @return 更新行数。 */
    int revokeSession(@Param("credentialHash") String credentialHash);

    /** 撤销匹配 hash 的设备令牌。 @param credentialHash 设备 token 哈希。 @return 更新行数。 */
    int revokeDevice(@Param("credentialHash") String credentialHash);

    /** 插入一次性配对码。 @param userId 配对所属 owner UUID。 @param codeHash 配对码哈希。 @param expiresAt 过期时间。 @return 配对记录 UUID 文本。 */
    String insertPairing(@Param("userId") String userId,
                         @Param("codeHash") String codeHash,
                         @Param("expiresAt") Instant expiresAt);

    /** 原子消费仍有效的配对码。 @param codeHash 配对码哈希。 @return owner UUID 文本；不可用时为 {@code null}。 */
    String consumePairing(@Param("codeHash") String codeHash);

    /** 查询配对码是否已消费。 @param codeHash 配对码哈希。 @return 已消费时为 {@code true}。 */
    boolean selectPairingConsumed(@Param("codeHash") String codeHash);

    /** 撤销 owner 下指定外部设备的旧令牌。 @param userId 设备所属 owner UUID。 @param externalDeviceId 客户端设备 ID。 @return 更新行数。 */
    int revokeDeviceForExternalId(@Param("userId") String userId,
                                  @Param("externalDeviceId") String externalDeviceId);

    /** 插入新的设备令牌记录。 @param userId 设备所属 owner UUID。 @param externalDeviceId 客户端设备 ID。 @param tokenHash 设备 token 哈希。 @param name 显示名称。 */
    void insertDevice(@Param("userId") String userId,
                      @Param("externalDeviceId") String externalDeviceId,
                      @Param("tokenHash") String tokenHash,
                      @Param("name") String name);
}
