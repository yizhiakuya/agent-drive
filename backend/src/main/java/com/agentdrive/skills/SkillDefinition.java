package com.agentdrive.skills;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一个可被 Agent 发现和读取的 Skill 完整定义。
 *
 * @param name owner 内稳定且规范化的 slug 名称
 * @param description 用于发现和选择 Skill 的短说明
 * @param instructions 模型读取后遵循的 Markdown 指令
 * @param enabled 自定义 Skill 是否可被 Agent 发现和读取
 * @param source {@code builtin} 或 {@code custom}
 * @param version 自定义 Skill 每次保存后递增的版本；内置 Skill 由 provider 给出
 * @param createdAt 创建时间 Unix 秒；内置 Skill 为空
 * @param updatedAt 最近更新时间 Unix 秒；内置 Skill 为空
 */
public record SkillDefinition(
        String name,
        String description,
        String instructions,
        boolean enabled,
        String source,
        int version,
        @JsonProperty("created_at") Double createdAt,
        @JsonProperty("updated_at") Double updatedAt
) {
}
