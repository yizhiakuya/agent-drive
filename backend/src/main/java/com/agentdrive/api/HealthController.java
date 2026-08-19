package com.agentdrive.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供不需要认证的服务存活探针。
 *
 * <p>该控制器只返回固定的服务标识和 {@code ok=true}，不读取数据库、文件存储或
 * 认证状态，因此可供反向代理和 systemd 在业务依赖不可用时仍然判断 HTTP 进程是否存活。
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    /**
     * 响应 {@code GET /api/v1/health} 探针。
     *
     * @return 包含 {@code ok=true} 和 {@code service=agent-drive} 的 JSON 对象；该响应不代表数据库、Worker 或外部 Provider 已就绪。
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "service", "agent-drive");
    }
}
