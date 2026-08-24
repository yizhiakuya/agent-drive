package com.agentdrive.identity;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/** Identity Service 的 setup、login、logout 和 token introspection 用例。 */
@Service
public class IdentityApplicationService {
    private static final Duration SESSION_TTL = Duration.ofDays(30);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final IdentityStore store;
    private final PasswordHasher passwords;

    /** 创建身份应用服务。 */
    public IdentityApplicationService(IdentityStore store, PasswordHasher passwords) {
        this.store = store;
        this.passwords = passwords;
    }

    /** 返回是否已经初始化 owner 密码。 */
    public Map<String, Object> status() {
        return Map.of("initialized", store.owner().isPresent());
    }

    /** 首次设置密码并签发 session。 */
    @Transactional
    public Map<String, Object> setup(String password) {
        String hash;
        try {
            hash = passwords.hash(password);
        } catch (IllegalArgumentException error) {
            throw new IdentityException(400, "invalid_password", error.getMessage());
        }
        UUID owner = store.createOwner(hash)
                .orElseThrow(() -> new IdentityException(409, "already_initialized", "password is already set"));
        return issueSession(owner);
    }

    /** 校验密码并签发新的 session。 */
    @Transactional
    public Map<String, Object> login(String password) {
        IdentityStore.Owner owner = store.owner()
                .orElseThrow(() -> new IdentityException(409, "not_initialized", "password is not set"));
        if (!passwords.matches(password, owner.passwordHash())) {
            throw new IdentityException(401, "authentication_failed", "invalid password");
        }
        return issueSession(owner.id());
    }

    /** 撤销 cookie/bearer 中的 session credential。 */
    @Transactional
    public Map<String, Object> logout(String cookie, String bearer) {
        boolean revoked = false;
        if (cookie != null && !cookie.isBlank()) revoked = store.revoke(CredentialHash.sha256(cookie)) || revoked;
        if (bearer != null && !bearer.isBlank()) revoked = store.revoke(CredentialHash.sha256(bearer)) || revoked;
        return Map.of("ok", true, "revoked", revoked);
    }

    /** 解析当前 token 的 owner 和 credential kind。 */
    public Map<String, Object> introspect(String credential) {
        if (credential == null || credential.isBlank()) {
            return Map.of("authenticated", false);
        }
        return store.introspect(CredentialHash.sha256(credential), Instant.now())
                .<Map<String, Object>>map(value -> Map.of(
                        "authenticated", true,
                        "owner_id", value.ownerId().toString(),
                        "kind", value.kind()))
                .orElseGet(() -> Map.of("authenticated", false));
    }

    private Map<String, Object> issueSession(UUID owner) {
        Instant expiresAt = Instant.now().plus(SESSION_TTL);
        String token = randomToken();
        store.createSession(owner, CredentialHash.sha256(token), expiresAt);
        return Map.of("ok", true, "owner_id", owner.toString(),
                "session_token", token, "expires_at", expiresAt.toString());
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 稳定的身份业务错误。 */
    public static final class IdentityException extends RuntimeException {
        private final int status;
        private final String code;

        /** 创建身份业务错误。 */
        public IdentityException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        /** 返回 HTTP 状态。 */
        public int status() {
            return status;
        }

        /** 返回稳定错误码。 */
        public String code() {
            return code;
        }
    }
}
