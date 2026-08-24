package com.agentdrive.identity;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Identity Service 自有 schema 的 owner 和 credential 持久化适配器。 */
@Repository
public class IdentityStore {
    private final JdbcTemplate jdbc;

    /** 创建 JDBC 存储适配器。 */
    public IdentityStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 读取唯一 owner 的密码哈希和 UUID。 */
    public Optional<Owner> owner() {
        return jdbc.query("SELECT id, password_hash FROM identity_owner WHERE username = 'owner'",
                rs -> rs.next() ? Optional.of(new Owner(UUID.fromString(rs.getString("id")), rs.getString("password_hash"))) : Optional.empty());
    }

    /** 首次创建 owner，已有 owner 时返回空。 */
    public Optional<UUID> createOwner(String passwordHash) {
        UUID id = UUID.randomUUID();
        try {
            int rows = jdbc.update("INSERT INTO identity_owner(id, username, password_hash) VALUES (?, 'owner', ?)",
                    id.toString(), passwordHash);
            return rows == 1 ? Optional.of(id) : Optional.empty();
        } catch (DuplicateKeyException error) {
            return Optional.empty();
        }
    }

    /** 创建带过期时间的 session credential。 */
    public void createSession(UUID ownerId, String tokenHash, Instant expiresAt) {
        jdbc.update("INSERT INTO identity_credentials(owner_id, kind, token_hash, expires_at) VALUES (?, 'SESSION', ?, ?)",
                ownerId.toString(), tokenHash, expiresAt);
    }

    /** 注册一个已由过渡期 API 签发的 session/device credential 摘要。 */
    public void registerCredential(UUID ownerId, String kind, String tokenHash, Instant expiresAt) {
        if (!"SESSION".equals(kind) && !"DEVICE".equals(kind)) {
            throw new IllegalArgumentException("credential kind is invalid");
        }
        jdbc.update("""
                INSERT INTO identity_credentials(owner_id, kind, token_hash, expires_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (token_hash) DO UPDATE SET owner_id = EXCLUDED.owner_id,
                  kind = EXCLUDED.kind, expires_at = EXCLUDED.expires_at, revoked_at = NULL
                """, ownerId, kind.toUpperCase(java.util.Locale.ROOT), tokenHash, expiresAt);
    }

    /** 按 hash 撤销尚未撤销的 credential。 */
    public boolean revoke(String tokenHash) {
        return jdbc.update("UPDATE identity_credentials SET revoked_at = CURRENT_TIMESTAMP WHERE token_hash = ? AND revoked_at IS NULL",
                tokenHash) > 0;
    }

    /** 校验 token，并返回当前 owner 和 credential kind。 */
    public Optional<Introspection> introspect(String tokenHash, Instant now) {
        return jdbc.query("SELECT owner_id, kind, expires_at, revoked_at FROM identity_credentials WHERE token_hash = ?",
                rs -> {
                    if (!rs.next() || rs.getTimestamp("expires_at").toInstant().isBefore(now)
                            || rs.getTimestamp("revoked_at") != null) {
                        return Optional.empty();
                    }
                    return Optional.of(new Introspection(UUID.fromString(rs.getString("owner_id")), rs.getString("kind")));
                }, tokenHash);
    }

    /** owner 身份值对象。 */
    public record Owner(UUID id, String passwordHash) {
    }

    /** introspection 结果值对象。 */
    public record Introspection(UUID ownerId, String kind) {
    }
}
