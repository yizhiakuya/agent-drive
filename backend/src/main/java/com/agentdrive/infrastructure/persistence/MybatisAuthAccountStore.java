package com.agentdrive.infrastructure.persistence;

import com.agentdrive.auth.AuthAccountStore;
import com.agentdrive.infrastructure.persistence.mapper.AuthAccountMapper;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 通过 MyBatis 持久化单 owner 认证数据、会话、设备和一次性配对码。
 * <p>配对消费和设备替换使用事务，确保配对码只消费一次且旧设备令牌撤销与新令牌写入不会分离。</p>
 */
public class MybatisAuthAccountStore implements AuthAccountStore {
    private final AuthAccountMapper mapper;

    /**
     * 保存认证账户 Mapper。
     * @param mapper 执行账户、session、device 和 pairing SQL 的 Mapper。
     */
    public MybatisAuthAccountStore(AuthAccountMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /**
     * 读取 owner 的密码哈希。
     * @return 数据库中的密码哈希；账户尚未创建时为空。
     */
    @Override
    public Optional<String> findOwnerPasswordHash() {
        return Optional.ofNullable(mapper.selectOwnerPasswordHash());
    }

    /**
     * 查询唯一 owner 账户的 UUID。
     * @return users 表中的 owner ID；没有账户或行缺少 id 时为空。
     * @throws IllegalArgumentException 数据库 id 不是合法 UUID 时抛出。
     */
    @Override
    public Optional<UUID> findOwnerId() {
        Map<String, Object> row = mapper.selectOwner();
        if (row == null || row.get("id") == null) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(String.valueOf(row.get("id"))));
    }

    /**
     * 创建 owner 账户并转换数据库返回的 UUID。
     * @param passwordHash 已哈希的密码，存储层不接收明文密码。
     * @return 新 owner 的 UUID；Mapper 没有返回 ID 时为空。
     */
    @Override
    public Optional<UUID> createOwner(String passwordHash) {
        String id = mapper.insertOwner(passwordHash);
        return id == null ? Optional.empty() : Optional.of(UUID.fromString(id));
    }

    /**
     * 写入带过期时间的 session credential hash。
     * @param userId session 所属 owner 的 UUID。
     * @param credentialHash session token 的哈希值。
     * @param expiresAt session 失效时间。
     */
    @Override
    public void createSession(UUID userId, String credentialHash, Instant expiresAt) {
        mapper.insertSession(userId.toString(), credentialHash, expiresAt);
    }

    /**
     * 撤销匹配 hash 的 session。
     * @param credentialHash session token 的哈希值。
     * @return 至少一行被撤销时为 {@code true}。
     */
    @Override
    public boolean revokeSession(String credentialHash) {
        return mapper.revokeSession(credentialHash) > 0;
    }

    /**
     * 撤销匹配 hash 的设备令牌。
     * @param credentialHash 设备 token 的哈希值。
     * @return 至少一行被撤销时为 {@code true}。
     */
    @Override
    public boolean revokeDevice(String credentialHash) {
        return mapper.revokeDevice(credentialHash) > 0;
    }

    /**
     * 在事务中创建一次性配对码记录。
     * @param userId 配对所属 owner 的 UUID。
     * @param codeHash 配对码的哈希值。
     * @param expiresAt 配对码过期时间。
     * @return 新配对记录的 UUID；Mapper 未返回 ID 时为空。
     */
    @Override
    @Transactional
    public Optional<UUID> createPairing(UUID userId, String codeHash, Instant expiresAt) {
        String id = mapper.insertPairing(userId.toString(), codeHash, expiresAt);
        return id == null ? Optional.empty() : Optional.of(UUID.fromString(id));
    }

    /**
     * 原子消费仍有效的配对码并返回其 owner。
     * @param codeHash 配对码的哈希值。
     * @return 配对所属 owner；不存在、过期或已消费时为空。
     */
    @Override
    public Optional<UUID> consumePairing(String codeHash) {
        String userId = mapper.consumePairing(codeHash);
        return userId == null ? Optional.empty() : Optional.of(UUID.fromString(userId));
    }

    /**
     * 查询配对码是否已经被消费。
     * @param codeHash 配对码的哈希值。
     * @return 数据库记录标记为 consumed 时为 {@code true}。
     */
    @Override
    public boolean pairingWasConsumed(String codeHash) {
        return mapper.selectPairingConsumed(codeHash);
    }

    /**
     * 在一个事务中消费配对码、撤销同一外部设备的旧令牌并插入新令牌。
     * @param codeHash 配对码哈希值。
     * @param externalDeviceId 客户端稳定的外部设备 ID。
     * @param tokenHash 新设备令牌哈希值。
     * @param name 新设备显示名称。
     * @return 配对所属 owner；配对码不可用时为空。
     */
    @Override
    @Transactional
    public Optional<UUID> consumePairingAndReplaceDevice(String codeHash,
                                                           String externalDeviceId,
                                                           String tokenHash,
                                                           String name) {
        String userId = mapper.consumePairing(codeHash);
        if (userId == null) {
            return Optional.empty();
        }
        mapper.revokeDeviceForExternalId(userId, externalDeviceId);
        mapper.insertDevice(userId, externalDeviceId, tokenHash, name);
        return Optional.of(UUID.fromString(userId));
    }

    /**
     * 在事务中替换指定 owner/外部设备的令牌。
     * @param userId 设备所属 owner 的 UUID。
     * @param externalDeviceId 客户端稳定的外部设备 ID。
     * @param tokenHash 新设备令牌哈希值。
     * @param name 新设备显示名称。
     */
    @Override
    @Transactional
    public void replaceDeviceToken(UUID userId, String externalDeviceId, String tokenHash, String name) {
        mapper.revokeDeviceForExternalId(userId.toString(), externalDeviceId);
        mapper.insertDevice(userId.toString(), externalDeviceId, tokenHash, name);
    }
}
