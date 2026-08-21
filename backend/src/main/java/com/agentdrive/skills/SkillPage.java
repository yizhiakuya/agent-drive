package com.agentdrive.skills;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 稳定分页的 Skill 发现结果。
 *
 * @param skills 当前页摘要
 * @param totalMatches 完整匹配数
 * @param returned 当前页实际数量
 * @param offset 当前页起始偏移
 * @param limit 规范化后的页大小
 * @param hasMore 是否存在下一页
 * @param nextOffset 下一页起始偏移；末页等于 totalMatches
 */
public record SkillPage(
        List<SkillSummary> skills,
        @JsonProperty("total_matches") int totalMatches,
        int returned,
        int offset,
        int limit,
        @JsonProperty("has_more") boolean hasMore,
        @JsonProperty("next_offset") int nextOffset
) {
    /**
     * 冻结当前页摘要，避免调用方修改 registry 结果。
     * @param skills 当前页摘要
     * @param totalMatches 完整匹配数
     * @param returned 当前页实际数量
     * @param offset 当前页起始偏移
     * @param limit 规范化后的页大小
     * @param hasMore 是否存在下一页
     * @param nextOffset 下一页起始偏移
     */
    public SkillPage {
        skills = List.copyOf(skills);
    }
}
