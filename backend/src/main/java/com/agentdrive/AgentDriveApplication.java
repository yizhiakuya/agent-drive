package com.agentdrive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Agent Drive 的 Spring Boot 启动入口。
 *
 * <p>默认启动 Web API；只有显式迁移模式会关闭嵌入式 Web 服务。任务 Worker 模式已移除。</p>
 */
@SpringBootApplication
public class AgentDriveApplication {

    /**
     * 创建并启动 Agent Drive 的 Spring 应用上下文。
     *
     * <p>启动前根据命令行模式决定是否使用非 Web 应用类型；启动失败时保留
     * Spring Boot 的异常，让进程以失败状态退出。</p>
     *
     * @param args 启动参数，支持 {@code --app.mode=migrate}
     */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AgentDriveApplication.class);
        if (isNonWebMode(args)) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        application.run(args);
    }

    /**
     * 判断启动参数是否要求以非 Web 模式运行。
     *
     * <p>只有数据库迁移模式会关闭 Web 服务；没有匹配参数时返回
     * {@code false}，因此默认启动 API。</p>
     *
     * @param args 待检查的命令行参数
     * @return 参数中包含迁移模式标记时为 {@code true}
     */
    private static boolean isNonWebMode(String[] args) {
        for (String arg : args) {
            if ("--app.mode=migrate".equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
