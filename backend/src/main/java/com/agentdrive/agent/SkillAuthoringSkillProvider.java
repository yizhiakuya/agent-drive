package com.agentdrive.agent;

import com.agentdrive.skills.BuiltinSkillProvider;
import com.agentdrive.skills.SkillDefinition;

import java.util.List;

/** 提供创建和维护自定义 Skill 的内置说明。 */
public final class SkillAuthoringSkillProvider implements BuiltinSkillProvider {
    public static final String NAME = "skill-authoring";

    /** {@inheritDoc} */
    @Override
    public List<SkillDefinition> skills() {
        return List.of(new SkillDefinition(
                NAME,
                "创建、编辑、启停和删除 Agent Drive 自定义 Skill。",
                """
                        # Skill Authoring

                        自定义 Skill 是 owner-scoped Markdown 指令，只能编排 Agent Drive 已登记工具。

                        ## Lifecycle

                        - 列表：`GET /api/v1/skills`
                        - 读取：`GET /api/v1/skills/{name}`
                        - 创建、更新或启停：`PUT /api/v1/skills/{name}`
                        - 删除：`DELETE /api/v1/skills/{name}`

                        PUT body 使用：

                        ```json
                        {
                          "description": "用于发现 Skill 的短说明",
                          "instructions": "完整 Markdown 指令",
                          "enabled": true
                        }
                        ```

                        ## Rules

                        - 名称是 1-64 位小写字母、数字或中划线 slug，保存后不改名。
                        - description 最多 500 字符，instructions 最多 16000 字符。
                        - 每个 owner 最多 100 个自定义 Skill。
                        - 停用 Skill 不会出现在 Agent 的 `read_skill` 结果中。
                        - 不写入 API key、Bearer、Cookie、任意 URL 或可执行脚本。
                        - 内置 Skill 只读，不能更新或删除。
                        """.trim(),
                true,
                "builtin",
                1,
                null,
                null
        ));
    }
}
