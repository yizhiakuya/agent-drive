package com.agentdrive.indexservice;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Index Service 的内部令牌和服务策略配置。 */
@ConfigurationProperties(prefix = "index")
public record IndexServiceProperties(String internalToken, Integer maxChunksPerDocument) {
    /** 固定配置默认值和上限。 */
    public IndexServiceProperties {
        internalToken = internalToken == null ? "" : internalToken.trim();
        maxChunksPerDocument = maxChunksPerDocument == null ? 4096
                : Math.max(1, Math.min(maxChunksPerDocument, 16_384));
    }
}
