package com.agentdrive.indexservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Index Service 启动入口。 */
@SpringBootApplication
@EnableConfigurationProperties(IndexServiceProperties.class)
public class IndexServiceApplication {
    /** 启动独立索引服务。 */
    public static void main(String[] args) {
        SpringApplication.run(IndexServiceApplication.class, args);
    }
}
