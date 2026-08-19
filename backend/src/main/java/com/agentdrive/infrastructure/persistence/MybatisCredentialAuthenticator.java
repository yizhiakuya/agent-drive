package com.agentdrive.infrastructure.persistence;

import com.agentdrive.auth.AuthenticatedPrincipal;
import com.agentdrive.auth.CredentialAuthenticator;
import com.agentdrive.auth.CredentialHash;
import com.agentdrive.infrastructure.persistence.mapper.CredentialMapper;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用 credential hash 从数据库解析认证主体。
 * <p>先查 session，再查 device token；原始 credential 只在内存中计算 SHA-256，
 * Mapper 永远接收 hash，不接收明文凭据。</p>
 */
public final class MybatisCredentialAuthenticator implements CredentialAuthenticator {
    private final CredentialMapper mapper;

    /**
     * 保存认证凭据查询 Mapper。
     * @param mapper 按 credential hash 查询 session/device owner 的 Mapper。
     */
    public MybatisCredentialAuthenticator(CredentialMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /**
     * 校验凭据并解析其 owner 和凭据类型。
     * @param credential Cookie/Bearer/设备令牌中的原始凭据；空值直接视为未认证。
     * @return session 命中时返回 SESSION 主体，device 命中时返回 DEVICE 主体，均未命中时为空。
     * @throws IllegalStateException 数据库行缺少 user_id 或 user_id 不是合法 UUID 时抛出。
     */
    @Override
    public Optional<AuthenticatedPrincipal> authenticate(String credential) {
        if (credential == null || credential.isBlank()) {
            return Optional.empty();
        }
        String hash = CredentialHash.sha256(credential);
        Map<String, Object> session = mapper.selectSessionOwner(hash);
        if (session != null) {
            return Optional.of(principal(session, AuthenticatedPrincipal.CredentialKind.SESSION));
        }
        Map<String, Object> device = mapper.selectDeviceOwner(hash);
        if (device != null) {
            return Optional.of(principal(device, AuthenticatedPrincipal.CredentialKind.DEVICE));
        }
        return Optional.empty();
    }

    /**
     * 把认证查询行转换成只包含 owner UUID 和凭据类型的主体。
     * @param row Mapper 返回的认证行。
     * @param kind 命中的凭据来源（session 或 device）。
     * @return 应用层认证主体。
     * @throws IllegalStateException 行没有 user_id 时抛出。
     */
    private AuthenticatedPrincipal principal(Map<String, Object> row,
                                             AuthenticatedPrincipal.CredentialKind kind) {
        Object userId = row.get("user_id");
        if (userId == null) {
            throw new IllegalStateException("credential row has no user_id");
        }
        return new AuthenticatedPrincipal(UUID.fromString(String.valueOf(userId)), kind);
    }
}
