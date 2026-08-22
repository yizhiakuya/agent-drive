package com.agentdrive.api.config;

import com.agentdrive.agent.LlmProviderConfig;
import com.agentdrive.agent.ChatModelCapabilities;
import com.agentdrive.infrastructure.LlmApiKeyCipher;
import com.agentdrive.infrastructure.EmbeddingConfigStore;
import com.agentdrive.infrastructure.LlmProviderConfigService;
import com.agentdrive.infrastructure.LlmProviderConfigView;
import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.agentdrive.api.ReactiveExecution.blocking;

/**
 * 提供 LLM Provider、模型探测和 Jina embedding 配置 API。
 *
 * <p>所有端点按 owner 读取或写入配置。LLM API key 和 embedding API key 只以
 * {@link LlmApiKeyCipher} 加密结果持久化，普通配置响应只返回掩码；专用回显端点仅接受
 * Web 会话并禁止缓存。当请求 key 留空时，仅在 provider 与 base URL 都和已存配置一致时
 * 复用旧 key。配置保存会先探测 Provider，embedding 保存成功后只保存配置；索引重建由用户
 * 或 Agent 显式调用索引 operation。
 */
@RestController
@Profile({"java-auth", "java-chat"})
@RequestMapping("/api/v1/config")
public final class ProviderConfigController {
    private static final Map<String, String> PROVIDER_TYPES = Map.of(
            "openai_compat", "OpenAI 兼容 (DeepSeek/Ollama/vLLM/Groq...)",
            "openai_responses", "OpenAI Responses",
            "anthropic", "Anthropic (Claude 及兼容)"
    );

    private final LlmProviderConfigService configs;
    private final LlmApiKeyCipher keyCipher;
    private final WebRequestPrincipalResolver principalResolver;
    private final ProviderProbeClient probe;
    private final EmbeddingConfigStore embeddingConfigs;
    private final EmbeddingProbeClient embeddingProbe;

    /**
     * 创建不启用 embedding 持久化依赖的兼容构造器。
     *
     * @param configs 读取和保存 owner-scoped LLM 配置的服务。
     * @param keyCipher 加密、解密 API key 的组件。
     * @param principalResolver 解析请求 owner 的认证组件。
     * @param objectMapper 用于 Provider 响应探测的 JSON 映射器。
     */
    public ProviderConfigController(LlmProviderConfigService configs,
                                    LlmApiKeyCipher keyCipher,
                                    WebRequestPrincipalResolver principalResolver,
                                    com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this(configs, keyCipher, principalResolver, objectMapper, null, new EmbeddingProbeClient(objectMapper));
    }

    /**
     * 创建完整配置控制器，并初始化两个外部 Provider 探测客户端。
     *
     * @param configs 读取和保存 owner-scoped LLM 配置的服务。
     * @param keyCipher 加密、解密 API key 的组件。
     * @param principalResolver 解析请求 owner 的认证组件。
     * @param objectMapper 用于解析 Provider 和 embedding 响应的 JSON 映射器。
     * @param embeddingConfigs 读取和保存 owner-scoped embedding 配置的存储；可为 null 以兼容无该功能的测试构造。
     */
    @Autowired
    public ProviderConfigController(LlmProviderConfigService configs,
                                    LlmApiKeyCipher keyCipher,
                                    WebRequestPrincipalResolver principalResolver,
                                    com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                    EmbeddingConfigStore embeddingConfigs) {
        this(configs, keyCipher, principalResolver, objectMapper, embeddingConfigs,
                new EmbeddingProbeClient(objectMapper));
    }

    /**
     * 创建可注入 embedding 探测器的控制器，供契约测试隔离外部网络。
     *
     * @param configs LLM 配置存储。
     * @param keyCipher API key 加解密器。
     * @param principalResolver 请求 owner 解析器。
     * @param objectMapper Provider 响应 JSON 映射器。
     * @param embeddingConfigs embedding 配置存储。
     * @param embeddingProbe embedding 连接探测器。
     */
    ProviderConfigController(LlmProviderConfigService configs,
                             LlmApiKeyCipher keyCipher,
                             WebRequestPrincipalResolver principalResolver,
                             com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                             EmbeddingConfigStore embeddingConfigs,
                             EmbeddingProbeClient embeddingProbe) {
        this.configs = configs;
        this.keyCipher = keyCipher;
        this.principalResolver = principalResolver;
        this.probe = new ProviderProbeClient(objectMapper);
        this.embeddingConfigs = embeddingConfigs;
        this.embeddingProbe = embeddingProbe;
    }

    /**
     * 响应 {@code GET /api/v1/config}，返回当前用户的 LLM 配置和 embedding 配置。
     *
     * @param exchange 用于解析 owner 的请求上下文。
     * @return 配置类型、当前非敏感字段和掩码 key 的异步 JSON 响应。
     */
    @GetMapping
    public Mono<Map<String, Object>> get(ServerWebExchange exchange) {
        return principalResolver.resolve(exchange)
                .flatMap(principal -> blocking(() -> current(principal.userId())));
    }

    /**
     * 响应 {@code GET /api/v1/config/status}，报告 LLM 和 embedding 配置是否存在。
     *
     * @param exchange 用于解析 owner 的请求上下文。
     * @return 包含 LLM configured 标志及 embedding provider、模型、掩码 key 的状态对象。
     */
    @GetMapping("/status")
    public Mono<Map<String, Object>> status(ServerWebExchange exchange) {
        return principalResolver.resolve(exchange)
                .flatMap(principal -> blocking(() -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("configured", configs.findForOwner(principal.userId()).isPresent());
                    Map<String, Object> embeddings = embeddingView(principal.userId());
                    if (embeddings == null) {
                        embeddings = Map.of("configured", false);
                    } else {
                        embeddings = new LinkedHashMap<>(embeddings);
                        embeddings.put("configured", true);
                    }
                    result.put("embeddings", embeddings);
                    return result;
                }));
    }

    /**
     * 响应 {@code POST /api/v1/config/api-key/reveal}，回显当前 owner 已保存的 LLM API key。
     *
     * <p>该端点只接受 Web 会话凭据，响应禁止缓存，也不登记到 Agent operation 目录。</p>
     *
     * @param exchange 用于解析 owner、凭据类型并设置敏感响应头的请求上下文。
     * @return 仅包含完整 {@code api_key} 的一次性响应。
     */
    @PostMapping("/api-key/reveal")
    public Mono<Map<String, String>> revealLlmApiKey(ServerWebExchange exchange) {
        ApiKeyRevealSupport.markNoStore(exchange);
        return principalResolver.resolve(exchange).flatMap(principal -> {
            ApiKeyRevealSupport.requireSession(principal);
            return blocking(() -> Map.of("api_key", savedLlmApiKey(principal.userId())));
        });
    }

    /**
     * 响应 {@code PUT /api/v1/config/embeddings}，保存并测试 Jina embedding 配置。
     *
     * @param payload 包含 provider、base URL、API key 和模型的请求体；provider 目前只接受 jina。
     * @param exchange 用于解析配置 owner 的请求上下文。
     * @return 保存结果和连接探测结果。
     */
    @PutMapping("/embeddings")
    public Mono<Map<String, Object>> embeddings(@RequestBody EmbeddingRequest payload,
                                                ServerWebExchange exchange) {
        return principalResolver.resolve(exchange)
                .flatMap(principal -> blocking(() -> saveEmbeddings(principal.userId(), payload)));
    }

    /**
     * 响应 {@code POST /api/v1/config/embeddings/api-key/reveal}，回显当前 owner 已保存的 embedding key。
     *
     * @param exchange 用于解析 owner、凭据类型并设置敏感响应头的请求上下文。
     * @return 仅包含完整 {@code api_key} 的一次性响应。
     */
    @PostMapping("/embeddings/api-key/reveal")
    public Mono<Map<String, String>> revealEmbeddingApiKey(ServerWebExchange exchange) {
        ApiKeyRevealSupport.markNoStore(exchange);
        return principalResolver.resolve(exchange).flatMap(principal -> {
            ApiKeyRevealSupport.requireSession(principal);
            return blocking(() -> Map.of("api_key", savedEmbeddingApiKey(principal.userId())));
        });
    }

    /**
     * 响应 {@code POST /api/v1/config}，探测并保存当前用户的 LLM Provider 配置。
     *
     * @param payload 包含 provider 类型、HTTP(S) base URL、可选 API key 和模型的请求体。
     * @param exchange 用于解析配置 owner 的请求上下文。
     * @return 连接测试结果和保存成功标志；探测失败时不会覆盖已有配置。
     */
    @PostMapping
    public Mono<Map<String, Object>> configure(@Valid @RequestBody LlmConfigRequest payload,
                                                ServerWebExchange exchange) {
        return principalResolver.resolve(exchange)
                .flatMap(principal -> blocking(() -> save(principal.userId(), payload)));
    }

    /**
     * 响应 {@code POST /api/v1/config/test}，只探测 LLM Provider 连接，不落盘。
     *
     * @param payload 要探测的 Provider、地址和 API key；key 不能依赖已存配置回退。
     * @param exchange 用于验证请求已认证的上下文。
     * @return Provider 返回的模型探测诊断结果。
     */
    @PostMapping("/test")
    public Mono<Map<String, Object>> test(@Valid @RequestBody LlmConfigRequest payload,
                                           ServerWebExchange exchange) {
        return principalResolver.resolve(exchange)
                .flatMap(ignored -> blocking(() -> probe(payload)));
    }

    /**
     * 响应 {@code POST /api/v1/config/models}，探测 Provider 可用模型列表，不落盘。
     *
     * @param payload 包含 Provider 类型、地址和可选 API key 的请求体；留空 key 只在地址与已存配置一致时复用。
     * @param exchange 用于解析 owner 并执行受保护探测的请求上下文。
     * @return Provider 返回的模型 ID 列表和探测状态。
     */
    @PostMapping("/models")
    public Mono<Map<String, Object>> models(@Valid @RequestBody ModelsRequest payload,
                                             ServerWebExchange exchange) {
        return principalResolver.resolve(exchange)
                .flatMap(principal -> blocking(() -> discoverModels(principal.userId(), payload)));
    }

    /**
     * 为内部 backend API 操作读取指定 owner 的完整配置视图。
     *
     * @param userId 配置所属用户 UUID。
     * @return 与 {@code GET /config} 相同结构的非敏感配置视图。
     */
    public Map<String, Object> currentForOwner(UUID userId) {
        return current(userId);
    }

    /**
     * 为内部调用构造 LLM 配置请求并执行同一套探测、key 加密和保存流程。
     *
     * @param userId 配置所属用户 UUID。
     * @param type Provider 类型。
     * @param baseUrl Provider HTTP(S) 地址。
     * @param apiKey 待保存的明文 key；仅在方法内部用于探测和加密。
     * @param model 默认聊天模型 ID。
     * @return 保存诊断结果，不返回明文 key。
     */
    public Map<String, Object> saveForOwner(UUID userId, String type, String baseUrl, String apiKey, String model) {
        return save(userId, new LlmConfigRequest(type, baseUrl, apiKey, model));
    }

    /**
     * 为内部调用执行一次不落盘的 LLM Provider 探测。
     *
     * @param type Provider 类型。
     * @param baseUrl Provider HTTP(S) 地址。
     * @param apiKey 探测请求使用的明文 key，不会写入响应。
     * @return 模型列表探测诊断结果。
     */
    public Map<String, Object> probeForOwner(String type, String baseUrl, String apiKey) {
        return probe(new LlmConfigRequest(type, baseUrl, apiKey, null));
    }

    /**
     * 为内部调用发现指定 owner 可用的模型列表。
     *
     * @param userId 配置所属用户 UUID；留空 key 时只从该 owner 的同地址配置回退。
     * @param type Provider 类型。
     * @param baseUrl Provider HTTP(S) 地址。
     * @param apiKey 可选探测 key。
     * @return 模型发现结果，不落盘配置。
     */
    public Map<String, Object> modelsForOwner(UUID userId, String type, String baseUrl, String apiKey) {
        return discoverModels(userId, new ModelsRequest(type, baseUrl, apiKey));
    }

    /**
     * 为内部调用保存 embedding 配置，并沿用 HTTP API 的测试语义。
     *
     * @param userId 配置所属用户 UUID。
     * @param provider embedding Provider，目前必须为 {@code jina}。
     * @param baseUrl embedding API 地址。
     * @param apiKey 明文 embedding key，仅用于探测和加密保存。
     * @param model embedding 模型 ID。
     * @return 保存和连接测试结果，不返回明文 key。
     */
    public Map<String, Object> saveEmbeddingsForOwner(UUID userId, String provider, String baseUrl, String apiKey, String model) {
        return saveEmbeddings(userId, new EmbeddingRequest(provider, baseUrl, apiKey, model));
    }

    /**
     * 组合 owner 的 LLM 和 embedding 配置视图。
     *
     * @param userId 配置所属用户 UUID。
     * @return 配置状态、支持的 Provider 类型、非敏感当前值和 API key 掩码。
     */
    private Map<String, Object> current(java.util.UUID userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("embeddings", embeddingView(userId));
        Optional<LlmProviderConfigView> stored = configs.findForOwner(userId);
        result.put("configured", stored.isPresent());
        result.put("provider_types", PROVIDER_TYPES);
        result.put("current", stored.map(view -> Map.of(
                "type", view.provider(),
                "base_url", view.baseUrl(),
                "model", view.model()
        )).orElse(null));
        result.put("llm", stored.map(view -> {
            String masked = configs.encryptedApiKeyForOwner(userId)
                    .map(keyCipher::decrypt)
                    .map(ProviderConfigController::mask)
                    .orElse("");
            return Map.of(
                    "type", view.provider(),
                    "base_url", view.baseUrl(),
                    "model", view.model(),
                    "supports_images", ChatModelCapabilities.supportsImages(view.provider(), view.model()),
                    "api_key_masked", masked
            );
        }).orElse(null));
        return result;
    }

    /**
     * 解密当前 owner 已保存的 LLM API key，供专用回显端点使用。
     *
     * @param userId 配置所属用户 UUID。
     * @return 非空明文 key。
     * @throws org.springframework.web.server.ResponseStatusException 未保存 key 时产生 404。
     */
    private String savedLlmApiKey(UUID userId) {
        return configs.encryptedApiKeyForOwner(userId)
                .filter(value -> value.length > 0)
                .map(keyCipher::decrypt)
                .filter(value -> !value.isBlank())
                .orElseThrow(ApiKeyRevealSupport::missingSavedKey);
    }

    /**
     * 解密当前 owner 已保存的 embedding API key，供专用回显端点使用。
     *
     * @param userId 配置所属用户 UUID。
     * @return 非空明文 key。
     * @throws org.springframework.web.server.ResponseStatusException 未保存 key 时产生 404。
     */
    private String savedEmbeddingApiKey(UUID userId) {
        if (embeddingConfigs == null) throw ApiKeyRevealSupport.missingSavedKey();
        return embeddingConfigs.find(userId)
                .map(EmbeddingConfigStore.EmbeddingConfig::encryptedApiKey)
                .filter(value -> value != null && value.length > 0)
                .map(keyCipher::decrypt)
                .filter(value -> !value.isBlank())
                .orElseThrow(ApiKeyRevealSupport::missingSavedKey);
    }

    /**
     * 校验、探测并保存 LLM 配置。
     *
     * <p>空 key 只有在 provider、规范化地址和已有配置一致时才解密复用；新 key
     * 先加密并计算指纹。Provider 探测失败会返回诊断而不调用保存服务。
     *
     * @param userId 配置所属用户 UUID。
     * @param payload HTTP 或内部调用提供的 LLM 配置。
     * @return 成功时包含探测结果和 {@code ok=true}，失败时包含错误诊断。
     */
    private Map<String, Object> save(java.util.UUID userId, LlmConfigRequest payload) {
        String provider = canonicalProvider(payload.type());
        String baseUrl = normalizeBaseUrl(payload.baseUrl());
        String model = requiredModel(payload.model());
        String apiKey = payload.apiKey() == null ? "" : payload.apiKey().trim();

        String keyToProbe = apiKey;
        byte[] encryptedKey = null;
        String fingerprint = "";
        if (keyToProbe.isBlank()) {
            Optional<LlmProviderConfigView> current = configs.findForOwner(userId);
            if (current.isEmpty()
                    || !sameProvider(current.get().provider(), provider)
                    || !sameUrl(current.get().baseUrl(), baseUrl)
                    || !current.get().apiKeyConfigured()) {
                return error("API Key 为空：修改协议/接口地址后请重新填写（未改则留空沿用已保存的 Key）");
            }
            encryptedKey = configs.encryptedApiKeyForOwner(userId).orElse(null);
            if (encryptedKey == null) {
                return error("API Key 为空：请先填写");
            }
            keyToProbe = keyCipher.decrypt(encryptedKey);
            fingerprint = current.get().apiKeyFingerprint();
        } else {
            encryptedKey = keyCipher.encrypt(keyToProbe);
            fingerprint = fingerprint(keyToProbe);
        }

        Map<String, Object> diagnostics = probe.listModels(provider, baseUrl, keyToProbe).asMap();
        if (!Boolean.TRUE.equals(diagnostics.get("ok"))) {
            return Map.of("ok", false, "test", diagnostics, "message", "连接测试失败");
        }
        configs.saveForOwner(userId, provider, baseUrl, model, encryptedKey, fingerprint);
        return Map.of("ok", true, "test", diagnostics, "message", "配置成功");
    }

    /**
     * 规范化配置并调用 Provider 的模型列表端点进行连接测试。
     *
     * @param payload 待探测的 LLM 配置；API key 不能为空。
     * @return Provider 探测客户端生成的诊断对象。
     */
    private Map<String, Object> probe(LlmConfigRequest payload) {
        String provider = canonicalProvider(payload.type());
        String baseUrl = normalizeBaseUrl(payload.baseUrl());
        String apiKey = payload.apiKey() == null ? "" : payload.apiKey().trim();
        if (apiKey.isBlank()) {
            return error("API Key 不能为空");
        }
        return probe.listModels(provider, baseUrl, apiKey).asMap();
    }

    /**
     * 发现模型并在允许的情况下安全回退到当前 owner 已存 key。
     *
     * @param userId 配置所属用户 UUID。
     * @param payload Provider 类型、地址和可选 key。
     * @return 模型列表及探测状态，并保留请求中的 Provider 类型字段。
     */
    private Map<String, Object> discoverModels(java.util.UUID userId, ModelsRequest payload) {
        String provider = canonicalProvider(payload.type());
        String baseUrl = normalizeBaseUrl(payload.baseUrl());
        String apiKey = payload.apiKey() == null ? "" : payload.apiKey().trim();
        if (apiKey.isBlank()) {
            Optional<LlmProviderConfigView> current = configs.findForOwner(userId);
            if (current.isPresent()
                    && sameProvider(current.get().provider(), provider)
                    && sameUrl(current.get().baseUrl(), baseUrl)
                    && current.get().apiKeyConfigured()) {
                apiKey = configs.encryptedApiKeyForOwner(userId)
                        .map(keyCipher::decrypt)
                        .orElse("");
            }
        }
        if (apiKey.isBlank()) {
            return error("API Key 为空：请先填写（或先保存当前配置再获取）");
        }
        Map<String, Object> result = probe.listModels(provider, baseUrl, apiKey).asMap();
        result.put("type", payload.type());
        return result;
    }

    /**
     * 将已存 embedding 配置转换成 API 状态视图。
     *
     * @param userId embedding 配置所属用户 UUID。
     * @return provider、地址、模型和掩码 key；没有 embedding 存储或配置时返回 {@code null}。
     */
    private Map<String, Object> embeddingView(java.util.UUID userId) {
        if (embeddingConfigs == null) return null;
        Optional<EmbeddingConfigStore.EmbeddingConfig> stored = embeddingConfigs.find(userId);
        if (stored.isEmpty()) return null;
        String masked = stored.get().encryptedApiKey() == null ? ""
                : mask(keyCipher.decrypt(stored.get().encryptedApiKey()));
        return Map.of(
                "provider", stored.get().provider(),
                "base_url", stored.get().baseUrl(),
                "model", stored.get().model(),
                "api_key_masked", masked
        );
    }

    /**
     * 校验并保存 Jina embedding 配置，随后测试连接；索引业务由调用方按需直接执行。
     *
     * <p>provider 固定为 {@code jina}；空地址/模型使用 Jina 默认值，空 key 仅在
     * provider、地址和模型都未改变时复用旧密文。连接测试成功后才持久化配置，
     * 因此测试失败时不会覆盖旧配置，也不会返回成功标志。
     *
     * @param userId 配置所属用户 UUID。
     * @param payload embedding 配置请求体。
     * @return 保存字段和连接测试结果。
     * @throws IllegalArgumentException provider 不是 jina 或无法得到 API key 时抛出。
     */
    private Map<String, Object> saveEmbeddings(java.util.UUID userId, EmbeddingRequest payload) {
        if (embeddingConfigs == null) return error("embedding persistence is unavailable");
        String provider = payload.provider() == null || payload.provider().isBlank()
                ? "jina" : payload.provider().trim().toLowerCase(Locale.ROOT);
        if (!"jina".equals(provider)) {
            throw new IllegalArgumentException("当前仅支持 Jina embedding provider");
        }
        String baseUrl = payload.baseUrl() == null || payload.baseUrl().isBlank()
                ? "https://api.jina.ai/v1" : normalizeBaseUrl(payload.baseUrl());
        String model = payload.model() == null || payload.model().isBlank()
                ? "jina-embeddings-v3" : payload.model().trim();
        String apiKey = payload.apiKey() == null ? "" : payload.apiKey().trim();
        Optional<EmbeddingConfigStore.EmbeddingConfig> current = embeddingConfigs.find(userId);
        if (apiKey.isBlank() && current.isPresent()
                && provider.equalsIgnoreCase(current.get().provider())
                && sameUrl(current.get().baseUrl(), baseUrl)
                && model.equals(current.get().model())
                && current.get().encryptedApiKey() != null) {
            apiKey = keyCipher.decrypt(current.get().encryptedApiKey());
        }
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("Embedding API Key 不能为空");
        }
        Map<String, Object> diagnostics = embeddingProbe.test(baseUrl, model, apiKey);
        if (!Boolean.TRUE.equals(diagnostics.get("ok"))) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("ok", false);
            failed.put("saved", null);
            failed.put("test", diagnostics);
            failed.put("message", "连接测试失败，配置未保存");
            return failed;
        }
        byte[] encrypted = keyCipher.encrypt(apiKey);
        embeddingConfigs.save(userId, new EmbeddingConfigStore.EmbeddingConfig(provider, baseUrl, model, encrypted));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("saved", Map.of("provider", provider, "model", model));
        result.put("test", diagnostics);
        return result;
    }

    /**
     * 将用户输入映射为内部支持的 Provider 标识。
     *
     * @param raw 用户提交的类型，可接受连字符形式的别名。
     * @return {@code openai_compat}、{@code openai_responses} 或 {@code anthropic}。
     * @throws IllegalArgumentException 输入为空或不在支持集合中。
     */
    private static String canonicalProvider(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Provider 类型不能为空");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "openai_compat", "openai_compatible" -> "openai_compat";
            case "openai_responses" -> "openai_responses";
            case "anthropic" -> "anthropic";
            default -> throw new IllegalArgumentException("未知 Provider 类型: " + raw);
        };
    }

    /**
     * 比较两个 Provider 输入在规范化后的内部标识是否相同。
     *
     * @param left 第一个 Provider 标识。
     * @param right 第二个 Provider 标识。
     * @return 两者代表同一受支持 Provider 时为 {@code true}。
     */
    private static boolean sameProvider(String left, String right) {
        return canonicalProvider(left).equals(canonicalProvider(right));
    }

    /**
     * 比较两个 URL 去除首尾斜杠后的规范化文本。
     *
     * @param left 第一个 base URL。
     * @param right 第二个 base URL。
     * @return 规范化字符串相同时为 {@code true}。
     */
    private static boolean sameUrl(String left, String right) {
        return normalizeBaseUrl(left).equals(normalizeBaseUrl(right));
    }

    /**
     * 去除 base URL 首尾空白和末尾斜杠。
     *
     * @param raw 用户提交的地址。
     * @return 用于比较和 Provider 请求的规范化地址。
     * @throws IllegalArgumentException 地址为空时抛出。
     */
    private static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("base_url 不能为空");
        }
        return raw.trim().replaceAll("/+$", "");
    }

    /**
     * 校验聊天模型 ID 并去除首尾空白。
     *
     * @param raw 用户提交的模型 ID。
     * @return 非空规范化模型 ID。
     * @throws IllegalArgumentException 模型为空时抛出。
     */
    private static String requiredModel(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("model 不能为空");
        }
        return raw.trim();
    }

    /**
     * 生成只保留 API key 前缀的展示值。
     *
     * @param value 待掩码的明文 key。
     * @return 空值为空字符串，短 key 只显示省略号，长 key 显示前六个字符和省略号。
     */
    private static String mask(String value) {
        return value == null || value.isEmpty()
                ? ""
                : value.length() > 6 ? value.substring(0, 6) + "…" : "…";
    }

    /**
     * 对 API key 计算 SHA-256 指纹，用于配置变更去重而不是作为认证凭据。
     *
     * @param value API key 明文。
     * @return UTF-8 输入的 64 位小写十六进制 SHA-256 字符串。
     */
    private static String fingerprint(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 将字节数组编码为小写十六进制字符串。
     *
     * @param bytes 待编码的摘要字节。
     * @return 每个字节对应两位十六进制字符的字符串。
     */
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    /**
     * 构造统一的失败诊断响应。
     *
     * @param message 面向客户端的错误说明，不应包含明文 API key。
     * @return {@code ok=false} 且带 {@code error} 字段的响应对象。
     */
    private static Map<String, Object> error(String message) {
        return Map.of("ok", false, "error", message);
    }

    /**
     * LLM 配置写入和连接测试请求体。
     *
     * <p>JSON 字段采用客户端契约的 snake_case 名称；API key 只在请求处理期间存在，
     * 保存时会被加密，响应不会回显明文。
     */
    public record LlmConfigRequest(
            @JsonProperty("type") @NotBlank @Size(max = 64) String type,
            @JsonProperty("base_url") @NotBlank @Size(max = 2048) String baseUrl,
            @JsonProperty("api_key") @Size(max = 4096) String apiKey,
            @JsonProperty("model") @Size(max = 256) String model
    ) {
    }

    /**
     * 模型列表探测请求体；与配置写入相比不包含模型字段，也不会落盘。
     */
    public record ModelsRequest(
            @JsonProperty("type") @NotBlank @Size(max = 64) String type,
            @JsonProperty("base_url") @NotBlank @Size(max = 2048) String baseUrl,
            @JsonProperty("api_key") @Size(max = 4096) String apiKey
    ) {
    }

    /**
     * Jina embedding 配置请求体，字段为空时由保存流程应用 Jina 默认地址和模型。
     */
    public record EmbeddingRequest(
            @JsonProperty("provider") String provider,
            @JsonProperty("base_url") String baseUrl,
            @JsonProperty("api_key") String apiKey,
            @JsonProperty("model") String model
    ) {
    }
}
