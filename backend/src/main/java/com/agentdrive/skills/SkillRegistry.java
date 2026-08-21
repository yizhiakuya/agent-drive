package com.agentdrive.skills;

import java.util.Optional;
import java.util.UUID;

/** 合并内置与 owner 自定义 Skill 的应用服务边界。 */
public interface SkillRegistry {
    /**
     * 搜索并分页列出 Skill。
     * @param userId 当前 owner
     * @param query 名称或说明查询，可为空
     * @param includeDisabled 是否包含已停用自定义 Skill
     * @param offset 起始偏移
     * @param limit 页大小
     * @return 稳定分页结果
     */
    SkillPage discover(UUID userId, String query, boolean includeDisabled, Integer offset, Integer limit);

    /**
     * 精确读取一个 Skill。
     * @param userId 当前 owner
     * @param name Skill 名称
     * @param includeDisabled 是否允许读取已停用自定义 Skill
     * @return 内置或当前 owner 的匹配 Skill
     */
    Optional<SkillDefinition> read(UUID userId, String name, boolean includeDisabled);

    /**
     * 创建或替换一个自定义 Skill。
     * @param userId 当前 owner
     * @param name Skill 名称
     * @param description 发现说明
     * @param instructions Markdown 指令
     * @param enabled 是否启用
     * @return 保存后的定义
     */
    SkillDefinition save(UUID userId, String name, String description, String instructions, boolean enabled);

    /**
     * 删除一个自定义 Skill。
     * @param userId 当前 owner
     * @param name Skill 名称
     * @return 实际删除时为 true
     */
    boolean delete(UUID userId, String name);
}
