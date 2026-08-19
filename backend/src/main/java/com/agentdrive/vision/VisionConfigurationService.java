package com.agentdrive.vision;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 处理视觉模型配置的规范化、连接测试、加密保存和脱敏状态展示。
 *
 * <p>视觉模型当前使用 OpenAI Chat Completions 兼容协议；API key 留空时只有 provider、地址和
 * 模型完全一致才复用旧 key，防止把凭据误发到新地址。</p>
 */
@Service
@Profile({"java-files", "java-auth", "java-chat"})
public final class VisionConfigurationService {
    private static final String PROVIDER = "openai_compat";
    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private final VisionConfigStore configs;
    private final VisionSecretCipher keyCipher;
    private final VisionModelClient client;

    /**
     * 创建视觉配置服务。
     * @param configs 视觉配置持久化端口。
     * @param keyCipher 视觉 API key 加密器端口。
     * @param client 视觉模型连接测试客户端。
     */
    public VisionConfigurationService(VisionConfigStore configs, VisionSecretCipher keyCipher, VisionModelClient client) {
        this.configs = configs;
        this.keyCipher = keyCipher;
        this.client = client;
    }

    /**
     * 返回 owner 当前视觉配置的非敏感视图。
     * @param userId 配置所属 owner UUID。
     * @return configured、provider、地址、模型和前缀掩码 key。
     */
    public Map<String, Object> current(UUID userId) {
        Optional<VisionConfigStore.VisionConfig> stored = configs.find(userId);
        if (stored.isEmpty()) return Map.of("configured", false, "provider", PROVIDER,
                "base_url", DEFAULT_BASE_URL, "model", "", "api_key_masked", "");
        String key = stored.get().encryptedApiKey() == null ? "" : keyCipher.decrypt(stored.get().encryptedApiKey());
        return Map.of("configured", true, "provider", stored.get().provider(),
                "base_url", stored.get().baseUrl(), "model", stored.get().model(), "api_key_masked", mask(key));
    }

    /**
     * 规范化、测试并保存视觉模型配置。
     * @param provider 当前只支持 openai_compat。
     * @param baseUrl OpenAI 兼容接口地址。
     * @param apiKey 明文 key，仅在本次调用期间使用。
     * @param model 视觉模型 ID。
     * @return 保存结果和连接测试结果，不包含明文 key。
     */
    public Map<String, Object> save(UUID userId, String provider, String baseUrl, String apiKey, String model) {
        String normalizedProvider = normalizeProvider(provider);
        String normalizedUrl = normalizeBaseUrl(baseUrl);
        String normalizedModel = requiredModel(model);
        String key = apiKey == null ? "" : apiKey.trim();
        Optional<VisionConfigStore.VisionConfig> current = configs.find(userId);
        if (key.isBlank() && current.isPresent()
                && normalizedProvider.equals(current.get().provider())
                && sameUrl(normalizedUrl, current.get().baseUrl())
                && normalizedModel.equals(current.get().model())
                && current.get().encryptedApiKey() != null) {
            key = keyCipher.decrypt(current.get().encryptedApiKey());
        }
        if (key.isBlank()) throw new IllegalArgumentException("视觉模型 API Key 不能为空");
        VisionRuntimeConfig.Config runtime = new VisionRuntimeConfig.Config(normalizedProvider, normalizedUrl, normalizedModel, key);
        Map<String, Object> test = client.test(runtime);
        if (!Boolean.TRUE.equals(test.get("ok"))) {
            return Map.of("ok", false, "test", test, "message", "视觉模型连接测试失败");
        }
        configs.save(userId, new VisionConfigStore.VisionConfig(normalizedProvider, normalizedUrl,
                normalizedModel, keyCipher.encrypt(key)));
        return Map.of("ok", true, "saved", Map.of("provider", normalizedProvider, "model", normalizedModel), "test", test);
    }

    /**
     * 查询当前视觉配置对应的 provider 模型目录。
     *
     * <p>空 API key 只有在 provider 和地址与已保存视觉配置一致时才解密复用；这和保存配置
     * 的凭据边界一致，避免用户改了地址后误把旧 key 发给新的服务。</p>
     *
     * @param userId 配置所属 owner UUID。
     * @param provider 当前视觉协议。
     * @param baseUrl 视觉 provider 基地址。
     * @param apiKey 本次请求提供的 API key，可为空。
     * @return 模型 ID 列表或不含密钥的探测错误。
     */
    public Map<String, Object> discoverModels(UUID userId, String provider, String baseUrl, String apiKey) {
        String normalizedProvider = normalizeProvider(provider);
        String normalizedUrl = normalizeBaseUrl(baseUrl);
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isBlank()) {
            Optional<VisionConfigStore.VisionConfig> current = configs.find(userId);
            if (current.isPresent()
                    && normalizedProvider.equals(current.get().provider())
                    && sameUrl(normalizedUrl, current.get().baseUrl())
                    && current.get().encryptedApiKey() != null) {
                key = keyCipher.decrypt(current.get().encryptedApiKey());
            }
        }
        if (key.isBlank()) {
            return Map.of("ok", false, "error", "视觉模型 API Key 为空：请先填写（或先保存当前配置再获取）");
        }
        return client.listModels(new VisionRuntimeConfig.Config(normalizedProvider, normalizedUrl, "", key));
    }

    /**
     * 把 provider 输入限制为当前支持的协议。
     * @param raw provider 文本。
     * @return 规范化 provider。
     */
    private String normalizeProvider(String raw) {
        String value = raw == null || raw.isBlank() ? PROVIDER : raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (!PROVIDER.equals(value)) throw new IllegalArgumentException("当前视觉模型仅支持 OpenAI 兼容协议");
        return value;
    }

    /**
     * 校验视觉 API 基地址，拒绝凭据、查询和 fragment。
     * @param raw 用户输入地址；空值使用 OpenAI 默认地址。
     * @return 去除末尾斜杠的地址。
     */
    private String normalizeBaseUrl(String raw) {
        String value = raw == null || raw.isBlank() ? DEFAULT_BASE_URL : raw.trim().replaceAll("/+$", "");
        URI uri = URI.create(value);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("视觉模型 base_url 必须是无凭据的 HTTP(S) 地址");
        }
        return value;
    }

    /**
     * 检查模型 ID 非空。
     * @param raw 模型 ID。
     * @return 去首尾空格后的模型 ID。
     */
    private String requiredModel(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("视觉模型 model 不能为空");
        return raw.trim();
    }

    /**
     * 比较两个规范化 URL。
     * @param left 第一个 URL。
     * @param right 第二个 URL。
     * @return 表示同一地址时为 true。
     */
    private boolean sameUrl(String left, String right) {
        return left.replaceAll("/+$", "").equals(right == null ? "" : right.replaceAll("/+$", ""));
    }

    /**
     * 只保留 API key 前缀用于设置页展示。
     * @param key 明文 key。
     * @return 掩码文本。
     */
    private static String mask(String key) {
        return key == null || key.isEmpty() ? "" : key.length() > 6 ? key.substring(0, 6) + "…" : "…";
    }
}
