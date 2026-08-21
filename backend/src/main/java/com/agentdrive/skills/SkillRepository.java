package com.agentdrive.skills;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 持久化 owner-scoped 自定义 Skill 的 repository 边界。 */
public interface SkillRepository {
    /**
     * 读取 owner 的全部自定义 Skill。
     * @param userId Skill owner
     * @return 按名称稳定排序的自定义 Skill
     */
    List<SkillDefinition> list(UUID userId);

    /**
     * 精确读取 owner 的一个自定义 Skill。
     * @param userId Skill owner
     * @param name 已规范化名称
     * @return 匹配定义；不存在时为空
     */
    Optional<SkillDefinition> find(UUID userId, String name);

    /**
     * 创建或替换一个自定义 Skill，并在数据库事务内执行数量上限保护。
     * @param userId Skill owner
     * @param name 已规范化名称
     * @param description 短说明
     * @param instructions Markdown 指令
     * @param enabled 是否启用
     * @param maxSkills owner 允许的最大自定义 Skill 数
     * @return 保存后的定义；新建被数量上限拒绝时为空
     */
    Optional<SkillDefinition> upsert(UUID userId, String name, String description,
                                     String instructions, boolean enabled, int maxSkills);

    /**
     * 删除 owner 的自定义 Skill。
     * @param userId Skill owner
     * @param name 已规范化名称
     * @return 实际删除时为 true
     */
    boolean delete(UUID userId, String name);
}
