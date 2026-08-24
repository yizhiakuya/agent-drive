package com.agentdrive.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Identity Service 启动入口。 */
@SpringBootApplication
@EnableConfigurationProperties(IdentityServiceProperties.class)
public class IdentityServiceApplication {
    /** 启动独立 owner 身份服务。 */
    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
