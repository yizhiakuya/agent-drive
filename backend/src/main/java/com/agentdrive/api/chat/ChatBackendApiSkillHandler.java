package com.agentdrive.api.chat;

import com.agentdrive.agent.BackendApiRequest;
import com.agentdrive.skills.SkillDefinition;
import com.agentdrive.skills.SkillRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 把 backend_api Skill operations 分发到 owner-scoped Skill registry。 */
@Component
@Profile("java-chat")
final class ChatBackendApiSkillHandler implements BackendApiOperationHandler {
    private static final Set<String> OPERATIONS = Set.of(
            "GET /api/v1/skills",
            "GET /api/v1/skills/{name}",
            "PUT /api/v1/skills/{name}",
            "DELETE /api/v1/skills/{name}"
    );

    private final SkillRegistry registry;

    /**
     * 创建 Skill operation handler。
     * @param registry owner-scoped Skill registry
     */
    ChatBackendApiSkillHandler(SkillRegistry registry) {
        this.registry = registry;
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> dispatch(String operation, BackendApiRequest request, UUID userId) {
        return switch (operation) {
            case "GET /api/v1/skills" -> Map.of("page", registry.discover(
                    userId,
                    BackendApiParams.parameter(request, "q", ""),
                    BackendApiParams.booleanParameter(request, "include_disabled"),
                    BackendApiParams.integerParameter(request, "offset", 0),
                    BackendApiParams.integerParameter(request, "limit", 20)));
            case "GET /api/v1/skills/{name}" -> Map.of("skill", read(request, userId));
            case "PUT /api/v1/skills/{name}" -> Map.of("skill", save(request, userId));
            case "DELETE /api/v1/skills/{name}" -> Map.of(
                    "deleted", BackendApiParams.requiredPath(request, "name"),
                    "ok", registry.delete(userId, BackendApiParams.requiredPath(request, "name")));
            default -> throw new IllegalArgumentException("Unsupported skill operation: " + operation);
        };
    }

    /**
     * 读取精确 Skill。
     * @param request backend_api 请求
     * @param userId 当前 owner
     * @return 完整 Skill
     */
    private SkillDefinition read(BackendApiRequest request, UUID userId) {
        String name = BackendApiParams.requiredPath(request, "name");
        return registry.read(userId, name, true)
                .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + name));
    }

    /**
     * 保存自定义 Skill。
     * @param request backend_api 请求
     * @param userId 当前 owner
     * @return 保存后的 Skill
     */
    private SkillDefinition save(BackendApiRequest request, UUID userId) {
        return registry.save(
                userId,
                BackendApiParams.requiredPath(request, "name"),
                BackendApiParams.required(request, "description"),
                BackendApiParams.required(request, "instructions"),
                !request.body().containsKey("enabled") || BackendApiParams.booleanParameter(request, "enabled")
        );
    }
}
