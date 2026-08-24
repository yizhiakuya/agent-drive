package com.agentdrive.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Identity Service 的内部认证和身份策略配置。 */
@ConfigurationProperties(prefix = "identity")
public record IdentityServiceProperties(String internalToken) {
    /** 在配置边界清理内部令牌。 */
    public IdentityServiceProperties {
        internalToken = internalToken == null ? "" : internalToken.trim();
    }
}
