package com.agentdrive.auth;

import java.util.UUID;

/**
 * 认证解析后的最小身份上下文。
 *
 * <p>userId 用于 owner-scoped 查询和写入，credentialKind 用于区分会话凭据与设备
 * 令牌；该记录不保存原始令牌，因此可以安全地在请求处理链中传递。</p>
 */
public record AuthenticatedPrincipal(UUID userId, CredentialKind credentialKind) {
    /**
     * 创建一个带有效身份类型的 principal。
     * @param userId 认证用户 UUID
     * @param credentialKind 凭据来源类型
     * @throws IllegalArgumentException 任一字段为空时抛出
     */
    public AuthenticatedPrincipal {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (credentialKind == null) {
            throw new IllegalArgumentException("credentialKind must not be null");
        }
    }

    /** 认证凭据的来源类别。 */
    public enum CredentialKind {
        SESSION,
        DEVICE
    }
}
