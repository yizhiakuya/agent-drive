package com.agentdrive.infrastructure;

import com.agentdrive.agent.ConfiguredChatModel;
import com.agentdrive.agent.LlmProviderConfig;
import com.agentdrive.agent.ProviderRuntimeResolver;
import com.agentdrive.agent.StreamingModelFactory;
import com.agentdrive.infrastructure.persistence.LlmProviderConfigStore;
import com.agentdrive.infrastructure.persistence.StoredLlmProviderConfig;

import java.util.UUID;

/**
 * 将数据库中的 owner-scoped LLM 配置解析为可调用的聊天模型。
 * <p>成功返回的对象同时持有流式模型和请求工厂，二者来自同一份 provider 配置。</p>
 */
public final class DatabaseProviderRuntimeResolver implements ProviderRuntimeResolver {
    private final LlmProviderConfigStore store;
    private final LlmApiKeyCipher keyCipher;
    private final StreamingModelFactory modelFactory;

    /**
     * 保存配置存储、密钥解密器和模型构造工厂。
     * @param store 查询 owner-scoped provider 配置的存储。
     * @param keyCipher 解密数据库 API key 的服务。
     * @param modelFactory 根据 provider 配置创建模型和请求工厂的组件。
     */
    public DatabaseProviderRuntimeResolver(LlmProviderConfigStore store,
                                           LlmApiKeyCipher keyCipher,
                                           StreamingModelFactory modelFactory) {
        this.store = store;
        this.keyCipher = keyCipher;
        this.modelFactory = modelFactory;
    }

    /**
     * 读取并解析 owner 的 provider 配置。
     * <p>空用户、未配置 provider 分别以明确异常失败；空密文按空 key 处理，非空密文必须先解密。</p>
     * @param userId 聊天请求所属 owner 的 UUID。
     * @return 同时包含流式模型和请求工厂的已配置模型。
     * @throws IllegalStateException 用户为空、没有配置 provider，或配置无法解析/解密时抛出。
     */
    @Override
    public ConfiguredChatModel resolve(UUID userId) {
        if (userId == null) {
            throw new IllegalStateException("authenticated user is required to resolve an LLM provider");
        }
        StoredLlmProviderConfig stored = store.find(userId)
                .orElseThrow(() -> new IllegalStateException("LLM provider is not configured"));
        String apiKey = stored.encryptedApiKey() == null ? "" : keyCipher.decrypt(stored.encryptedApiKey());
        LlmProviderConfig config = new LlmProviderConfig(
                LlmProviderConfig.ProviderType.from(stored.provider()),
                apiKey,
                stored.baseUrl(),
                stored.model()
        );
        return new ConfiguredChatModel(modelFactory.create(config), modelFactory.requestFactory(config),
                stored.provider(), stored.model());
    }
}
