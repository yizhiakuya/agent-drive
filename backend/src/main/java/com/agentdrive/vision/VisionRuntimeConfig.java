package com.agentdrive.vision;

import java.util.Optional;
import java.util.UUID;

/**
 * 提供按 owner 隔离的视觉模型运行时配置。
 * {@link Config#apiKey()} 只允许用于服务端 HTTP 请求，不得写入日志、operation 结果或响应。
 */
public interface VisionRuntimeConfig {
    /**
     * 读取当前 owner 的视觉模型配置。
     * @param userId 配置所属 owner UUID。
     * @return 已配置的视觉 provider；未配置时为空。
     */
    Optional<Config> find(UUID userId);

    /**
     * 视觉请求所需的配置快照。
     * @param provider 视觉 provider 标识。
     * @param baseUrl OpenAI 兼容 API 基地址。
     * @param model 视觉模型 ID。
     * @param apiKey 服务端内存中的明文 API key。
     */
    record Config(String provider, String baseUrl, String model, String apiKey) {
    }
}
