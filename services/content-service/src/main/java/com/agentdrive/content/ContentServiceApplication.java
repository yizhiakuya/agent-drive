package com.agentdrive.content;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Content Intelligence Service 启动入口。 */
@SpringBootApplication
@EnableConfigurationProperties(ContentServiceProperties.class)
public class ContentServiceApplication {
    /** 启动独立内容理解服务。 */
    public static void main(String[] args) {
        SpringApplication.run(ContentServiceApplication.class, args);
    }
}
