package com.agentdrive.index;

import java.util.Optional;
import java.util.UUID;

/**
 * 提供按用户隔离的 embedding 运行时配置。
 * 其中 {@link Config#apiKey()} 是敏感凭据，调用方只能用于向 embedding provider 发请求，不能写入日志或返回给客户端。
 */
public interface EmbeddingRuntimeConfig {
    /**
     * 读取用户当前保存的 embedding provider 配置。
     * 未配置时返回空值，调用方应将其解释为无法执行向量化，而不是使用默认密钥或默认服务商。
     *
     * @param userId embedding 配置归属用户的 UUID。
     * @return 已配置的 provider、地址、模型和密钥；未配置时为空。
     */
    Optional<Config> find(UUID userId);

    /**
     * 一次 embedding 调用所需的 provider 配置快照。
     * {@code baseUrl} 不包含请求路径，Jina 实现会在其后追加 {@code /embeddings}；{@code apiKey} 只在服务端内存中使用。
     *
     * @param provider provider 名称，当前生产契约为 {@code jina}。
     * @param baseUrl provider 的 HTTP(S) 基础地址。
     * @param model 要请求的 embedding 模型名。
     * @param apiKey provider API 密钥。
     */
    record Config(String provider, String baseUrl, String model, String apiKey) {
    }
}
