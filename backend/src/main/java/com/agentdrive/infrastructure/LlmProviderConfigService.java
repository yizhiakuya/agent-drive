package com.agentdrive.infrastructure;

import java.util.Optional;
import java.util.UUID;

/**
 * 暴露 owner-scoped LLM provider 配置的应用服务端口。
 * <p>查询返回给设置页的视图不会包含 API key 明文；内部运行时需要密文时通过单独方法取得。</p>
 */
public interface LlmProviderConfigService {
    /**
     * 查询设置页可展示的 provider 配置摘要。
     * @param userId 配置所属 owner 的 UUID。
     * @return provider、地址、模型及 key 配置状态；未配置时为空。
     */
    Optional<LlmProviderConfigView> findForOwner(UUID userId);

    /**
     * 读取内部模型运行时所需的 API key 密文。
     * @param userId 配置所属 owner 的 UUID。
     * @return 非空密文字节；没有配置 key 时为空。调用方不能把结果返回给客户端。
     */
    Optional<byte[]> encryptedApiKeyForOwner(UUID userId);

    /**
     * 保存 owner 的 provider 配置及 API key 密文。
     * @param userId 配置所属 owner 的 UUID。
     * @param provider provider 类型，例如 {@code openai} 或兼容协议标识。
     * @param baseUrl provider 请求基地址。
     * @param model 默认聊天模型名。
     * @param encryptedApiKey 已加密的 API key；未配置 key 时可为空。
     * @param apiKeyFingerprint 用于判断 key 是否变化的不可逆指纹，不是 API key 明文。
     */
    void saveForOwner(UUID userId,
                      String provider,
                      String baseUrl,
                      String model,
                      byte[] encryptedApiKey,
                      String apiKeyFingerprint);
}
