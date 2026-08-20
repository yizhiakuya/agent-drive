package com.agentdrive.skills;

/** 自定义 Skill 文本落库前的不可逆敏感内容清理边界。 */
@FunctionalInterface
public interface SkillTextSanitizer {
    /**
     * 清理 instructions 或 description 中的凭据模式。
     * @param value 已完成空白和长度校验的文本
     * @return 可安全持久化并交给模型读取的文本
     */
    String sanitize(String value);
}
