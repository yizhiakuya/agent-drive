package com.agentdrive.agentservice;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent Service 内部令牌和运行策略配置。 */
@ConfigurationProperties(prefix = "agent")
public record AgentServiceProperties(String internalToken, Integer maxEventRows) {
    public AgentServiceProperties {
        internalToken = internalToken == null ? "" : internalToken.trim();
        maxEventRows = maxEventRows == null ? 4096 : Math.max(1, Math.min(maxEventRows, 8192));
    }
}
