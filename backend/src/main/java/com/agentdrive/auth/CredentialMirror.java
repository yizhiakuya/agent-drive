package com.agentdrive.auth;

import java.time.Instant;
import java.util.UUID;

/** 认证 credential 向独立 Identity Service 的过渡双写端口。 */
public interface CredentialMirror {
    /** 注册 session/device 的哈希和过期时间。 */
    void register(UUID ownerId, AuthenticatedPrincipal.CredentialKind kind,
                  String tokenHash, Instant expiresAt);

    /** 撤销一个 credential hash。 */
    void revoke(String tokenHash);

    /** 不启用远程 Identity 时使用的空实现。 */
    static CredentialMirror noop() {
        return new CredentialMirror() {
            @Override
            public void register(UUID ownerId, AuthenticatedPrincipal.CredentialKind kind,
                                 String tokenHash, Instant expiresAt) {
            }

            @Override
            public void revoke(String tokenHash) {
            }
        };
    }
}
