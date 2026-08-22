package com.agentdrive.api.automation;

import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.files.FileStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

import static com.agentdrive.api.ReactiveExecution.blocking;

/**
 * 暴露当前用户最近一次自动化报告的只读端点。
 *
 * <p>控制器先认证 owner，再把报告文件扫描移到 bounded-elastic；报告不存在时由
 * {@link AutomationReportService} 返回空报告，而不是把缺失的 Agent/notes 目录当成 API 故障。
 */
@RestController
@Profile({"java-auth", "java-chat"})
@RequestMapping("/api/v1/automation")
public final class AutomationController {
    private final WebRequestPrincipalResolver principalResolver;
    private final AutomationReportService reports;

    /**
     * 创建使用文件存储构造报告服务的控制器。
     *
     * @param principalResolver 解析当前请求 owner 的认证组件。
     * @param files 读取 owner-scoped 自动化报告文件的存储服务。
     */
    public AutomationController(WebRequestPrincipalResolver principalResolver, FileStorageService files) {
        this(principalResolver, new AutomationReportService(files));
    }

    /**
     * 创建自动化报告控制器。
     *
     * @param principalResolver 解析当前请求 owner 的认证组件。
     * @param reports 查找并截断最近自动化报告的服务。
     */
    @Autowired
    public AutomationController(WebRequestPrincipalResolver principalResolver, AutomationReportService reports) {
        this.principalResolver = principalResolver;
        this.reports = reports;
    }

    /**
     * 响应 {@code GET /api/v1/automation/latest}，返回当前用户最近的自动化报告。
     *
     * @param exchange 用于解析报告 owner 的请求上下文。
     * @return 包含 {@code last_run} 和最多 2000 个字符报告正文的异步响应。
     */
    @GetMapping("/latest")
    public Mono<Map<String, Object>> latest(ServerWebExchange exchange) {
        return principalResolver.resolve(exchange)
                .flatMap(principal -> blocking(() -> latestFor(principal.userId())));
    }

    /**
     * 为指定 owner 查询最近的自动化报告。
     *
     * @param userId 报告文件所属用户 UUID。
     * @return 报告服务返回的结果；没有匹配文件时 report 为 {@code null}。
     */
    private Map<String, Object> latestFor(java.util.UUID userId) {
        return reports.latestFor(userId);
    }

}
