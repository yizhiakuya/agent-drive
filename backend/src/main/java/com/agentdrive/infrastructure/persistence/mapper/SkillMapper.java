package com.agentdrive.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 读写 owner-scoped 自定义 Skill 的 MyBatis Mapper。 */
@Mapper
public interface SkillMapper {
    /**
     * 列出 owner 的全部自定义 Skill。
     * @param userId owner UUID 字符串
     * @return 按名称排序的 Skill 行
     */
    List<Map<String, Object>> selectAll(@Param("userId") String userId);

    /**
     * 精确读取 owner 的 Skill。
     * @param userId owner UUID 字符串
     * @param name 规范化名称
     * @return Skill 行；不存在时为空
     */
    Map<String, Object> selectByName(@Param("userId") String userId, @Param("name") String name);

    /**
     * 在 owner advisory transaction lock 内创建或更新 Skill。
     * @param userId owner UUID 字符串
     * @param name 规范化名称
     * @param description 短说明
     * @param instructions Markdown 指令
     * @param enabled 是否启用
     * @param maxSkills owner 最大自定义 Skill 数
     * @return 保存后的行；数量上限拒绝新建时为空
     */
    Map<String, Object> upsert(@Param("userId") String userId,
                               @Param("name") String name,
                               @Param("description") String description,
                               @Param("instructions") String instructions,
                               @Param("enabled") boolean enabled,
                               @Param("maxSkills") int maxSkills);

    /**
     * 删除 owner 的 Skill。
     * @param userId owner UUID 字符串
     * @param name 规范化名称
     * @return 删除行数
     */
    int delete(@Param("userId") String userId, @Param("name") String name);
}
