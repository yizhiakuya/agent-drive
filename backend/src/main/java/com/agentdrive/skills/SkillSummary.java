package com.agentdrive.skills;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Skill 列表使用的不含完整 instructions 的轻量摘要。
 *
 * @param name Skill slug
 * @param description 发现说明
 * @param enabled 是否允许 Agent 使用
 * @param source {@code builtin} 或 {@code custom}
 * @param version 当前版本
 * @param updatedAt 最近更新时间 Unix 秒；内置 Skill 为空
 */
public record SkillSummary(
        String name,
        String description,
        boolean enabled,
        String source,
        int version,
        @JsonProperty("updated_at") Double updatedAt
) {
    /**
     * 从完整定义生成列表摘要。
     * @param definition 完整 Skill 定义
     * @return 不包含 instructions 的摘要
     */
    public static SkillSummary from(SkillDefinition definition) {
        return new SkillSummary(
                definition.name(),
                definition.description(),
                definition.enabled(),
                definition.source(),
                definition.version(),
                definition.updatedAt()
        );
    }
}
