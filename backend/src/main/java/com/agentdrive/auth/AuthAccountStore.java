package com.agentdrive.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 认证账户、会话、设备令牌和配对码的持久化边界。
 *
 * <p>实现只保存凭据哈希而不是明文；session、device 和 pairing 数据必须在数据库
 * 事务中按哈希查找/失效。配对交换接口还承担“一次性消费配对码并替换设备令牌”的
 * 原子性要求。</p>
 */
public interface AuthAccountStore {
    /** 返回 owner 账户当前保存的密码哈希；未初始化时为空。 */
    Optional<String> findOwnerPasswordHash();

    /** 返回 owner 账户 UUID；数据库中没有 owner 时为空。 */
    Optional<UUID> findOwnerId();

    /**
     * 在尚未初始化时创建 owner 账户。
     * @param passwordHash 已使用密码哈希算法处理的密码
     * @return 新 owner UUID；已有账户时为空
     */
    Optional<UUID> createOwner(String passwordHash);

    /**
     * 保存一个会话凭据哈希及其过期时间。
     * @param userId 会话所属 owner
     * @param credentialHash 会话令牌的哈希，不得是明文令牌
     * @param expiresAt 会话失效时间
     */
    void createSession(UUID userId, String credentialHash, Instant expiresAt);

    /**
     * 按凭据哈希撤销会话。
     * @param credentialHash 要撤销的会话令牌哈希
     * @return 找到并标记撤销时为 true
     */
    boolean revokeSession(String credentialHash);

    /**
     * 按凭据哈希撤销设备令牌。
     * @param credentialHash 要撤销的设备令牌哈希
     * @return 找到并标记撤销时为 true
     */
    boolean revokeDevice(String credentialHash);

    /**
     * 保存一个有有效期和数量上限的配对码哈希。
     * @param userId 生成配对码的 owner
     * @param codeHash 配对码哈希
     * @param expiresAt 配对码失效时间
     * @return 配对记录 ID；超过活动配对码上限时为空
     */
    Optional<UUID> createPairing(UUID userId, String codeHash, Instant expiresAt);

    /**
     * 消费一个尚未过期且未使用的配对码。
     * @param codeHash 客户端配对码哈希
     * @return 被消费的 owner UUID；无效、过期或已消费时为空
     */
    Optional<UUID> consumePairing(String codeHash);

    /**
     * 判断配对码是否曾被成功消费，用于区分“已使用”和“无效/过期”。
     * @param codeHash 配对码哈希
     * @return 已消费时为 true
     */
    boolean pairingWasConsumed(String codeHash);

    /**
     * 在一个事务中消费配对码并写入/替换设备令牌。
     * @param codeHash 配对码哈希
     * @param externalDeviceId 客户端设备 ID
     * @param tokenHash 新设备令牌哈希
     * @param name 可选设备显示名
     * @return 配对码所属 owner；事务未成功时为空
     */
    Optional<UUID> consumePairingAndReplaceDevice(String codeHash, String externalDeviceId,
                                                   String tokenHash, String name);

    /**
     * 写入设备令牌哈希；相同外部设备 ID 的旧令牌应由实现替换或失效。
     * @param userId 设备所属 owner
     * @param externalDeviceId 客户端设备 ID
     * @param tokenHash 新设备令牌哈希
     * @param name 可选设备显示名
     */
    void replaceDeviceToken(UUID userId, String externalDeviceId, String tokenHash, String name);
}
