package com.agentdrive.auth;

import java.util.Optional;

/**
 * 将请求中的原始会话/设备令牌解析为不含秘密值的认证 principal。
 *
 * <p>实现应先哈希令牌再查询存储，并在无效、过期或撤销时返回空 Optional。</p>
 */
public interface CredentialAuthenticator {
    /**
     * 验证一个原始凭据并返回其 owner 和凭据类型。
     * @param credential Cookie 或 Authorization header 中的原始令牌
     * @return 有效凭据的 principal；无效或空凭据时为空
     */
    Optional<AuthenticatedPrincipal> authenticate(String credential);
}
