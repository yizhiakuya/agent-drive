package com.agentdrive.api.devices;

import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.devices.DeviceStore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提供当前用户设备注册表的查询、注册和撤销端点。
 *
 * <p>每个端点先解析请求主体，再把同步的 {@link DeviceStore} 操作移到
 * bounded-elastic 调度器。所有读写均以解析出的用户 ID 为作用域，设备移除由
 * {@code DeviceStore} 负责撤销关联令牌并持久化状态。
 */
@RestController
@Profile({"java-auth", "java-chat"})
@RequestMapping("/api/v1/devices")
public final class DeviceController {
    private final DeviceStore devices;
    private final WebRequestPrincipalResolver principalResolver;

    /**
     * 创建设备 API 控制器。
     *
     * @param devices 读写 owner-scoped 设备注册表的存储服务。
     * @param principalResolver 从 Cookie/Bearer 凭据解析当前用户。
     */
    public DeviceController(DeviceStore devices, WebRequestPrincipalResolver principalResolver) {
        this.devices = devices;
        this.principalResolver = principalResolver;
    }

    /**
     * 响应 {@code GET /api/v1/devices}，列出当前用户已注册的设备。
     *
     * @param exchange 用于解析 owner 身份的请求上下文。
     * @return 包含 {@code devices} 数组的异步响应。
     */
    @GetMapping
    public Mono<Map<String, Object>> list(ServerWebExchange exchange) {
        return principalResolver.resolve(exchange)
                .flatMap(principal -> blocking(() -> Map.of("devices", devices.list(principal.userId()))));
    }

    /**
     * 响应 {@code POST /api/v1/devices/register}，登记或更新当前用户的设备元数据。
     *
     * @param request 包含客户端 ID、平台信息、应用版本和同步状态的请求体。
     * @param exchange 用于解析设备归属用户的请求上下文。
     * @return {@link DeviceStore} 返回的设备记录和注册结果。
     */
    @PostMapping("/register")
    public Mono<Map<String, Object>> register(@Valid @RequestBody DeviceRegisterRequest request,
                                               ServerWebExchange exchange) {
        return principalResolver.resolve(exchange)
                .flatMap(principal -> blocking(() -> devices.register(
                        principal.userId(), request.deviceId(), request.name(), request.model(),
                        request.platform(), request.appVersion(), request.sync()
                )));
    }

    /**
     * 响应 {@code DELETE /api/v1/devices/{deviceId}}，撤销当前用户的设备。
     *
     * @param deviceId 客户端提交的设备 ID，而非数据库主键。
     * @param exchange 用于限制删除作用域到当前用户的请求上下文。
     * @return 包含被删除设备 ID 和 {@code tokens_revoked=true} 的响应。
     * @throws DeviceNotFoundException 设备不属于当前用户或不存在时抛出，由 advice 转为 404。
     */
    @DeleteMapping("/{deviceId}")
    public Mono<Map<String, Object>> remove(@PathVariable String deviceId, ServerWebExchange exchange) {
        return principalResolver.resolve(exchange)
                .flatMap(principal -> blocking(() -> {
                    if (!devices.remove(principal.userId(), deviceId)) {
                        throw new DeviceNotFoundException("device not found");
                    }
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("removed", deviceId);
                    result.put("tokens_revoked", true);
                    return result;
                }));
    }

    /**
     * 将设备存储的阻塞调用移出 WebFlux 事件循环。
     *
     * @param operation 要执行的设备查询或写入操作。
     * @param <T> 操作结果类型。
     * @return 在 bounded-elastic 调度器运行操作的异步结果。
     */
    private <T> Mono<T> blocking(java.util.concurrent.Callable<T> operation) {
        return Mono.fromCallable(operation).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 设备注册请求体。
     *
     * <p>JSON 使用客户端约定的 {@code device_id}/{@code app_version} 字段；缺省的
     * 展示字段归一化为空字符串，平台缺省为 {@code android}，以便存储层不接收 null。
     */
    public record DeviceRegisterRequest(
            @JsonProperty("device_id") @NotBlank @Size(max = 128) String deviceId,
            @Size(max = 128) String name,
            @Size(max = 128) String model,
            @Size(max = 64) String platform,
            @JsonProperty("app_version") @Size(max = 64) String appVersion,
            Map<String, Object> sync
    ) {
        /**
         * 规范化设备注册请求中的可选字符串字段。
         *
         * @param deviceId 客户端稳定设备 ID，不能为空。
         * @param name 设备展示名称，可为空。
         * @param model 设备型号，可为空。
         * @param platform 客户端平台；为空时归一化为 {@code android}。
         * @param appVersion 客户端版本，可为空。
         * @param sync 客户端上报的同步状态对象，可为 {@code null}。
         */
        public DeviceRegisterRequest {
            if (name == null) name = "";
            if (model == null) model = "";
            if (platform == null) platform = "android";
            if (appVersion == null) appVersion = "";
        }
    }

    /**
     * 表示当前用户无法访问目标设备。
     */
    public static final class DeviceNotFoundException extends RuntimeException {
        /**
         * 创建带有 API 错误消息的设备缺失异常。
         *
         * @param message 返回给客户端的缺失原因。
         */
        public DeviceNotFoundException(String message) {
            super(message);
        }
    }
}
