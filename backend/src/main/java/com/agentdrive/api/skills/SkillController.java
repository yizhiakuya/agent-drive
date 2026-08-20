package com.agentdrive.api.skills;

import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.skills.SkillDefinition;
import com.agentdrive.skills.SkillPage;
import com.agentdrive.skills.SkillRegistry;
import com.agentdrive.skills.SkillRegistryException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.concurrent.Callable;

/** 提供 owner-scoped Skill 列表、读取、保存、启停和删除 API。 */
@RestController
@Profile("java-chat")
@RequestMapping("/api/v1/skills")
public final class SkillController {
    private final SkillRegistry registry;
    private final WebRequestPrincipalResolver principalResolver;

    /**
     * 创建 Skill API 控制器。
     * @param registry Skill 应用服务
     * @param principalResolver 当前 owner resolver
     */
    public SkillController(SkillRegistry registry, WebRequestPrincipalResolver principalResolver) {
        this.registry = registry;
        this.principalResolver = principalResolver;
    }

    /**
     * 分页列出当前 owner 可见 Skill。
     * @param query 名称/说明查询
     * @param includeDisabled 是否包含停用自定义 Skill
     * @param offset 起始偏移
     * @param limit 页大小
     * @param exchange 请求上下文
     * @return Skill 分页结果
     */
    @GetMapping
    public Mono<SkillPage> list(@RequestParam(name = "q", defaultValue = "") String query,
                                @RequestParam(name = "include_disabled", defaultValue = "false") boolean includeDisabled,
                                @RequestParam(defaultValue = "0") Integer offset,
                                @RequestParam(defaultValue = "20") Integer limit,
                                ServerWebExchange exchange) {
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() ->
                registry.discover(principal.userId(), query, includeDisabled, offset, limit)));
    }

    /**
     * 精确读取 Skill 完整定义。
     * @param name Skill 名称
     * @param exchange 请求上下文
     * @return 完整 Skill
     */
    @GetMapping("/{name}")
    public Mono<Map<String, Object>> get(@PathVariable String name, ServerWebExchange exchange) {
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() -> Map.of(
                "skill", registry.read(principal.userId(), name, true)
                        .orElseThrow(() -> new SkillRegistryException(404, "skill_not_found", "Skill 不存在"))
        )));
    }

    /**
     * 创建或替换自定义 Skill。
     * @param name Skill slug
     * @param request Skill 内容和启用状态
     * @param exchange 请求上下文
     * @return 保存后的 Skill
     */
    @PutMapping("/{name}")
    public Mono<Map<String, Object>> save(@PathVariable String name,
                                          @Valid @RequestBody SkillRequest request,
                                          ServerWebExchange exchange) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "skill body is required");
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() -> Map.of(
                "skill", registry.save(principal.userId(), name, request.description(),
                        request.instructions(), request.enabled() == null || request.enabled())
        )));
    }

    /**
     * 删除自定义 Skill。
     * @param name Skill slug
     * @param exchange 请求上下文
     * @return 删除名称
     */
    @DeleteMapping("/{name}")
    public Mono<Map<String, Object>> delete(@PathVariable String name, ServerWebExchange exchange) {
        return principalResolver.resolve(exchange).flatMap(principal -> blocking(() -> {
            if (!registry.delete(principal.userId(), name)) {
                throw new SkillRegistryException(404, "skill_not_found", "Skill 不存在");
            }
            return Map.of("deleted", name);
        }));
    }

    /**
     * 将阻塞 registry 调用移到 bounded-elastic 并映射稳定 HTTP 状态。
     * @param operation registry 查询或变更
     * @param <T> 响应类型
     * @return 异步结果
     */
    private <T> Mono<T> blocking(Callable<T> operation) {
        return Mono.fromCallable(operation)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(SkillRegistryException.class, error ->
                        new ResponseStatusException(HttpStatusCode.valueOf(error.status()), error.getMessage(), error));
    }

    /**
     * 自定义 Skill 写入请求。
     * @param description 发现说明
     * @param instructions Markdown 指令
     * @param enabled 是否启用；为空时默认 true
     */
    public record SkillRequest(
            @NotBlank @Size(max = 500) String description,
            @NotBlank @Size(max = 16000) String instructions,
            Boolean enabled
    ) {
    }
}
