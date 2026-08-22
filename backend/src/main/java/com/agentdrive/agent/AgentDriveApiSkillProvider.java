package com.agentdrive.agent;

import com.agentdrive.skills.BuiltinSkillProvider;
import com.agentdrive.skills.SkillDefinition;

import java.util.Comparator;
import java.util.List;

/** 根据当前 operation catalog 动态生成 Agent Drive API 内置 Skill。 */
public final class AgentDriveApiSkillProvider implements BuiltinSkillProvider {
    public static final String NAME = "agent-drive-api";

    private final OperationCatalog catalog;

    /**
     * 创建动态 API Skill provider。
     * @param catalog 当前模型可调用的 operation allowlist
     */
    public AgentDriveApiSkillProvider(OperationCatalog catalog) {
        this.catalog = catalog;
    }

    /** {@inheritDoc} */
    @Override
    public List<SkillDefinition> skills() {
        return List.of(new SkillDefinition(
                NAME,
                "Agent Drive 已登记后端 operation、风险边界和调用规则。",
                instructions(),
                true,
                "builtin",
                1,
                null,
                null
        ));
    }

    /**
     * 生成与 operation catalog 同源的 Markdown 指令。
     * @return 包含调用边界和完整登记 operation 的 Skill 正文
     */
    private String instructions() {
        StringBuilder text = new StringBuilder("""
                # Agent Drive API

                使用 `backend_api` 前先 discover，再调用返回的精确 operation。

                - 不构造未登记 URL、请求头或凭据。
                - `path_params` 只放路径模板占位符；查询字符串放 `query_params`；JSON 放 `body`。
                - green 为只读；yellow 会探测外部服务；ask/auto 模式下 red 必须完成确认后执行，full 模式按用户授权直接执行。
                - `PUT /api/v1/skills/{name}` 的 body 使用 `description`、`instructions`、`enabled`。
                - Skill 指令只能编排已登记工具，不能增加新的系统权限。

                ## Registered operations
                """);
        catalog.operations().stream()
                .sorted(Comparator.comparing(OperationDefinition::operation))
                .forEach(operation -> text.append("- `")
                        .append(operation.operation())
                        .append("` [")
                        .append(operation.risk())
                        .append("] - ")
                        .append(operation.summary())
                        .append('\n'));
        return text.toString().trim();
    }
}
