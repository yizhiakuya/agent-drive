package com.agentdrive.api.config;

import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.vision.VisionConfigurationService;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.UUID;

/**
 * 提供 owner-scoped 视觉模型配置读取、保存和连接测试接口。
 *
 * <p>视觉模型配置与文本 embedding 分开保存；这样图片识别可使用多模态模型，而文本语义
 * embedding 仍保持 Jina 配置不变。</p>
 */
@RestController
@Profile({"java-auth", "java-chat"})
@RequestMapping("/api/v1/config/vision")
public final class VisionConfigController {
    private final VisionConfigurationService configs;
    private final WebRequestPrincipalResolver principalResolver;

    /**
     * 创建视觉配置控制器。
     * @param configs 视觉配置校验、测试和持久化服务。
     * @param principalResolver 解析 Cookie/Bearer owner 的认证组件。
     */
    public VisionConfigController(VisionConfigurationService configs, WebRequestPrincipalResolver principalResolver) {
        this.configs = configs;
        this.principalResolver = principalResolver;
    }

    /**
     * 响应 {@code GET /api/v1/config/vision}，返回脱敏的视觉模型配置。
     * @param exchange 用于解析配置 owner 的请求上下文。
     * @return 当前视觉模型配置状态。
     */
    @GetMapping
    public Mono<Map<String, Object>> get(ServerWebExchange exchange) {
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(
                () -> configs.current(principal.userId())));
    }

    /**
     * 响应 {@code POST /api/v1/config/vision/models}，探测当前视觉 provider 的模型目录。
     * @param payload provider、地址和可选 API key。
     * @param exchange 用于解析配置 owner 的请求上下文。
     * @return 模型 ID 列表或安全的探测错误。
     */
    @PostMapping("/models")
    public Mono<Map<String, Object>> models(@Valid @RequestBody VisionModelsRequest payload,
                                            ServerWebExchange exchange) {
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() -> configs.discoverModels(
                principal.userId(), payload.provider(), payload.baseUrl(), payload.apiKey())));
    }

    /**
     * 响应 {@code PUT /api/v1/config/vision}，测试通过后保存视觉模型配置。
     * @param payload provider、地址、API key 和模型。
     * @param exchange 用于解析配置 owner 的请求上下文。
     * @return 保存结果、测试诊断和脱敏字段。
     */
    @PutMapping
    public Mono<Map<String, Object>> save(@Valid @RequestBody VisionRequest payload,
                                          ServerWebExchange exchange) {
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() -> configs.save(
                principal.userId(), payload.provider(), payload.baseUrl(), payload.apiKey(), payload.model())));
    }

    /**
     * 为内部 backend_api 调用读取 owner 的视觉配置。
     * @param userId 配置所属 owner UUID。
     * @return 脱敏配置视图。
     */
    public Map<String, Object> currentForOwner(UUID userId) {
        return configs.current(userId);
    }

    /**
     * 为内部 backend_api 调用探测 owner 当前视觉 provider 的模型目录。
     * @param userId 配置所属 owner UUID。
     * @param provider provider 标识。
     * @param baseUrl API 基地址。
     * @param apiKey 明文 key，仅用于本次探测或安全回退。
     * @return 模型 ID 列表或不含密钥的探测错误。
     */
    public Map<String, Object> modelsForOwner(UUID userId, String provider, String baseUrl, String apiKey) {
        return configs.discoverModels(userId, provider, baseUrl, apiKey);
    }

    /**
     * 为内部 backend_api 调用保存 owner 的视觉配置。
     * @param userId 配置所属 owner UUID。
     * @param provider provider 标识。
     * @param baseUrl API 基地址。
     * @param apiKey 明文 key，仅用于本次测试和加密保存。
     * @param model 视觉模型 ID。
     * @return 保存和测试结果。
     */
    public Map<String, Object> saveForOwner(UUID userId, String provider, String baseUrl, String apiKey, String model) {
        return configs.save(userId, provider, baseUrl, apiKey, model);
    }

    /**
     * 把阻塞的数据库和 provider 测试移出 WebFlux 事件循环。
     * @param operation 要执行的配置操作。
     * @param <T> 返回值类型。
     * @return bounded-elastic 调度后的异步结果。
     */
    private <T> Mono<T> blocking(java.util.concurrent.Callable<T> operation) {
        return Mono.fromCallable(operation).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 视觉配置请求体；API key 留空时只在地址和 provider 未改变时沿用旧值。
     * @param provider 当前支持 openai_compat。
     * @param baseUrl OpenAI 兼容 API 地址。
     * @param apiKey 待保存的 API key。
     * @param model 视觉模型 ID。
     */
    public record VisionRequest(
            @JsonProperty("provider") @Size(max = 64) String provider,
            @JsonProperty("base_url") @Size(max = 2048) String baseUrl,
            @JsonProperty("api_key") @Size(max = 4096) String apiKey,
            @JsonProperty("model") @Size(max = 256) String model
    ) {
    }

    /**
     * 视觉模型目录探测请求体；API key 留空时按 owner 的视觉配置安全回退。
     * @param provider 当前只支持 openai_compat。
     * @param baseUrl OpenAI 兼容 API 地址。
     * @param apiKey 本次探测使用的 API key。
     */
    public record VisionModelsRequest(
            @JsonProperty("provider") @Size(max = 64) String provider,
            @JsonProperty("base_url") @Size(max = 2048) String baseUrl,
            @JsonProperty("api_key") @Size(max = 4096) String apiKey
    ) {
    }
}
