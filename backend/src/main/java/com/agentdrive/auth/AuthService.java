package com.agentdrive.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent Drive 的 owner 认证和设备配对服务。
 *
 * <p>密码只以 PBKDF2 哈希写入 {@link AuthAccountStore}；登录产生 30 天会话令牌，
 * 设备配对产生短期一次性代码并换取设备令牌。服务返回原始令牌只供当前响应使用，
 * 存储层始终接收 SHA-256 哈希。调用方负责在 HTTP 层执行频率限制和 cookie/bearer
 * 解析。</p>
 */
public final class AuthService {
    public static final String OWNER_USERNAME = "owner";
    public static final Duration SESSION_TTL = Duration.ofDays(30);
    public static final Duration PAIRING_TTL = Duration.ofMinutes(5);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthAccountStore store;
    private final PasswordHasher passwords;

    /**
     * 绑定认证数据存储和密码哈希器。
     * @param store 保存 owner、会话、设备和配对状态的存储
     * @param passwords 负责 PBKDF2 哈希和验证的服务
     */
    public AuthService(AuthAccountStore store, PasswordHasher passwords) {
        this.store = store;
        this.passwords = passwords;
    }

    /**
     * 判断 owner 是否已经设置密码。
     * @return 存储中存在密码哈希时为 true
     */
    public boolean initialized() {
        return store.findOwnerPasswordHash().isPresent();
    }

    /**
     * 首次设置 owner 密码并立即签发会话。
     *
     * <p>密码必须为 8 至 128 个字符；创建 owner 由存储层保证只能成功一次，已有
     * owner 时转换为 {@link PasswordAlreadySetException}。</p>
     * @param password 首次设置的明文密码，仅在内存中参与哈希
     * @return 新会话令牌及 30 天过期时间
     * @throws InvalidPasswordException 密码长度不合法时抛出
     * @throws PasswordAlreadySetException owner 已初始化时抛出
     */
    public LoginResult setup(String password) {
        validateSetupPassword(password);
        String passwordHash = passwords.hash(password);
        UUID userId = store.createOwner(passwordHash)
                .orElseThrow(() -> new PasswordAlreadySetException("password is already set"));
        return issueSession(userId);
    }

    /**
     * 校验 owner 密码并创建新的会话令牌。
     * @param password 待验证的明文密码
     * @return 新会话令牌及过期时间
     * @throws NotInitializedException 尚未设置 owner 密码时抛出
     * @throws AuthenticationFailedException 密码不匹配时抛出
     */
    public LoginResult login(String password) {
        String passwordHash = store.findOwnerPasswordHash()
                .orElseThrow(() -> new NotInitializedException("password is not set"));
        if (!passwords.matches(password, passwordHash)) {
            throw new AuthenticationFailedException("invalid password");
        }
        UUID userId = ownerId()
                .orElseThrow(() -> new IllegalStateException("owner account has no id"));
        return issueSession(userId);
    }

    /**
     * 撤销 cookie 会话和 bearer 对应的会话/设备凭据。
     *
     * <p>空凭据被忽略；bearer 会同时尝试按 session 和 device 撤销，以兼容两种令牌
     * 来源。方法只把是否至少撤销一项作为结果，不返回令牌内容。</p>
     * @param cookieCredential Cookie 中的会话令牌
     * @param bearerCredential Authorization Bearer 中的会话或设备令牌
     * @return 至少一个凭据成功撤销时为 true
     */
    public boolean logout(String cookieCredential, String bearerCredential) {
        boolean revoked = false;
        if (cookieCredential != null && !cookieCredential.isBlank()) {
            revoked = store.revokeSession(CredentialHash.sha256(cookieCredential)) || revoked;
        }
        if (bearerCredential != null && !bearerCredential.isBlank()) {
            String hash = CredentialHash.sha256(bearerCredential);
            boolean sessionRevoked = store.revokeSession(hash);
            boolean deviceRevoked = store.revokeDevice(hash);
            revoked = sessionRevoked || deviceRevoked || revoked;
        }
        return revoked;
    }

    /**
     * 为已认证 owner 创建或替换设备令牌。
     * @param userId 当前 owner UUID
     * @param externalDeviceId 客户端设备 ID，最长 128 字符
     * @param name 可选设备名称，最长 128 字符
     * @return 只在本次响应中返回的明文设备令牌及设备 ID
     * @throws InvalidCredentialException userId 为空时抛出
     * @throws InvalidDeviceException 设备 ID 或名称不合法时抛出
     */
    public DeviceTokenResult issueDeviceToken(UUID userId, String externalDeviceId, String name) {
        requireUser(userId);
        validateDevice(externalDeviceId, name);
        String token = randomToken();
        store.replaceDeviceToken(userId, externalDeviceId, CredentialHash.sha256(token), name);
        return new DeviceTokenResult(token, externalDeviceId);
    }

    /**
     * 为 owner 创建五分钟有效的随机配对码。
     * @param userId 生成配对码的 owner UUID
     * @return 明文配对码及剩余有效秒数
     * @throws InvalidCredentialException userId 为空时抛出
     * @throws PairingLimitException 活动配对码达到存储层上限时抛出
     */
    public PairingResult issuePairing(UUID userId) {
        requireUser(userId);
        String code = randomToken();
        Instant expiresAt = Instant.now().plus(PAIRING_TTL);
        store.createPairing(userId, CredentialHash.sha256(code), expiresAt)
                .orElseThrow(() -> new PairingLimitException("too many active pairing codes"));
        return new PairingResult(code, (int) PAIRING_TTL.toSeconds());
    }

    /**
     * 用配对码原子换取设备令牌。
     *
     * <p>存储层同时消费配对码并写入设备令牌；失败后再查询是否曾消费，以便把重复
     * 使用与无效/过期区分开。</p>
     * @param code 明文配对码，仅在内存中立即哈希
     * @param externalDeviceId 新设备 ID
     * @param name 可选设备名称
     * @return 只在本次响应中返回的明文设备令牌
     * @throws InvalidPairingException 配对码为空、过期、无效或已使用时抛出
     * @throws InvalidDeviceException 设备信息不合法时抛出
     */
    public DeviceTokenResult exchangePairing(String code, String externalDeviceId, String name) {
        if (code == null || code.isBlank()) {
            throw new InvalidPairingException("pairing code is invalid or expired");
        }
        validateDevice(externalDeviceId, name);
        String token = randomToken();
        String codeHash = CredentialHash.sha256(code);
        if (store.consumePairingAndReplaceDevice(
                        codeHash,
                        externalDeviceId,
                        CredentialHash.sha256(token),
                        name
                ).isEmpty()) {
            if (store.pairingWasConsumed(codeHash)) {
                throw new InvalidPairingException("pairing code has already been used");
            }
            throw new InvalidPairingException("pairing code is invalid or expired");
        }
        return new DeviceTokenResult(token, externalDeviceId);
    }

    /**
     * 仅在认证配置存在时读取 owner UUID，避免未初始化数据被当成有效身份。
     * @return 已初始化 owner 的 UUID；否则为空
     */
    private Optional<UUID> ownerId() {
        return store.findOwnerPasswordHash().flatMap(ignored -> store.findOwnerId());
    }

    /**
     * 生成 30 天会话令牌并将其哈希写入存储。
     * @param userId 会话所属 owner UUID
     * @return 明文会话令牌及过期时间
     */
    private LoginResult issueSession(UUID userId) {
        Instant expiresAt = Instant.now().plus(SESSION_TTL);
        String token = randomToken();
        store.createSession(userId, CredentialHash.sha256(token), expiresAt);
        return new LoginResult(token, expiresAt);
    }

    /**
     * 生成 32 字节随机 URL-safe 无填充令牌。
     * @return 可放入 Cookie 或 Bearer 的随机令牌
     */
    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 确认调用方已经解析出认证用户。
     * @param userId 认证用户 UUID
     * @throws InvalidCredentialException userId 为空时抛出
     */
    private void requireUser(UUID userId) {
        if (userId == null) {
            throw new InvalidCredentialException("authenticated user is required");
        }
    }

    /**
     * 校验设备 ID 和显示名长度。
     * @param externalDeviceId 客户端设备 ID
     * @param name 可选设备显示名
     * @throws InvalidDeviceException ID 为空/超过 128 字符或名称超过 128 字符时抛出
     */
    private void validateDevice(String externalDeviceId, String name) {
        if (externalDeviceId == null || externalDeviceId.isBlank() || externalDeviceId.length() > 128) {
            throw new InvalidDeviceException("device_id must be 1-128 characters");
        }
        if (name != null && name.length() > 128) {
            throw new InvalidDeviceException("name must be at most 128 characters");
        }
    }

    /**
     * 校验首次设置密码的长度边界。
     * @param password 待设置的明文密码
     * @throws InvalidPasswordException 密码为空、短于 8 或长于 128 字符时抛出
     */
    private void validateSetupPassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new InvalidPasswordException("password must be 8-128 characters");
        }
    }

    /** 登录成功或首次设置后返回的会话令牌及过期时间。 */
    public record LoginResult(String sessionToken, Instant expiresAt) {
    }

    /** 设备注册或配对成功后返回的明文设备令牌和设备 ID。 */
    public record DeviceTokenResult(String token, String deviceId) {
    }

    /** 新建配对码及其有效期秒数；配对码只应通过受保护响应返回。 */
    public record PairingResult(String code, int expiresIn) {
    }

    /** 表示密码长度或格式不符合认证策略。 */
    public static class InvalidPasswordException extends RuntimeException {
        /** @param message 面向 API 层的错误说明 */
        public InvalidPasswordException(String message) {
            super(message);
        }
    }

    /** 表示重复执行首次 owner 初始化。 */
    public static class PasswordAlreadySetException extends RuntimeException {
        /** @param message 面向 API 层的错误说明 */
        public PasswordAlreadySetException(String message) {
            super(message);
        }
    }

    /** 表示登录或依赖 owner 时系统尚未完成初始化。 */
    public static class NotInitializedException extends RuntimeException {
        /** @param message 面向 API 层的错误说明 */
        public NotInitializedException(String message) {
            super(message);
        }
    }

    /** 表示提供的密码无法通过已存哈希验证。 */
    public static class AuthenticationFailedException extends RuntimeException {
        /** @param message 面向 API 层的错误说明 */
        public AuthenticationFailedException(String message) {
            super(message);
        }
    }

    /** 表示请求缺少可用于 owner-scoped 操作的认证身份。 */
    public static class InvalidCredentialException extends RuntimeException {
        /** @param message 面向 API 层的错误说明 */
        public InvalidCredentialException(String message) {
            super(message);
        }
    }

    /** 表示设备 ID 或设备名称不符合长度约束。 */
    public static class InvalidDeviceException extends RuntimeException {
        /** @param message 面向 API 层的错误说明 */
        public InvalidDeviceException(String message) {
            super(message);
        }
    }

    /** 表示认证入口超过调用频率限制。 */
    public static class RateLimitExceededException extends RuntimeException {
        /** @param message 面向 API 层的错误说明 */
        public RateLimitExceededException(String message) {
            super(message);
        }
    }

    /** 表示活动配对码数量已达到存储策略上限。 */
    public static class PairingLimitException extends RuntimeException {
        /** @param message 面向 API 层的错误说明 */
        public PairingLimitException(String message) {
            super(message);
        }
    }

    /** 表示配对码为空、无效、过期或已被消费。 */
    public static class InvalidPairingException extends RuntimeException {
        /** @param message 面向 API 层的错误说明 */
        public InvalidPairingException(String message) {
            super(message);
        }
    }
}
