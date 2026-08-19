package com.agentdrive.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.nio.file.Path;

import static org.springframework.web.reactive.function.server.RouterFunctions.resources;

/**
 * 将静态导出的前端目录注册为 WebFlux 资源路由。
 *
 * <p>目录由 {@code app.frontend-dir} 配置，默认指向后端工作目录上一级的
 * {@code frontend/out}。这里只负责把 URL 资源映射到文件系统，SPA 深链接的
 * {@code index.html} 回退由 {@link FrontendSpaFallbackWebFilter} 处理。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class FrontendResourceConfiguration {
    /**
     * 创建前端静态资源路由。
     *
     * @param frontendDir 前端导出目录的配置值；方法会转为绝对规范化路径后再创建资源根。
     * @return 匹配静态资源请求的 WebFlux 路由函数。
     */
    @Bean
    RouterFunction<ServerResponse> frontendResources(
            @Value("${app.frontend-dir:../frontend/out}") String frontendDir
    ) {
        Path root = Path.of(frontendDir).toAbsolutePath().normalize();
        return resources("/**", new FileSystemResource(root + java.io.File.separator));
    }
}
