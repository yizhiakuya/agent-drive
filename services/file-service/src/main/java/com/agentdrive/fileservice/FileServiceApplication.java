package com.agentdrive.fileservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** File Service 启动入口。 */
@SpringBootApplication
@EnableConfigurationProperties(FileServiceProperties.class)
public class FileServiceApplication {
    /** 启动独立 owner 文件内容服务。 */
    public static void main(String[] args) {
        SpringApplication.run(FileServiceApplication.class, args);
    }
}
