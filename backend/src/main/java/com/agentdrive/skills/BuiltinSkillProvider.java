package com.agentdrive.skills;

import java.util.List;

/** 提供随应用版本发布、不可由 owner 修改的内置 Skill。 */
public interface BuiltinSkillProvider {
    /**
     * 返回当前应用版本提供的内置 Skill。
     * @return 不可变或可安全复制的内置定义列表
     */
    List<SkillDefinition> skills();
}
