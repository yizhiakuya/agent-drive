package com.agentdrive.content;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 提供本机 systemd 使用的进程存活探针。 */
@RestController
public final class ContentHealthController {
    /** 返回不包含 provider 配置的进程状态。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "content");
    }
}
