package com.agentdrive.skills;

/** 带稳定 HTTP 状态和错误代码的 Skill 业务异常。 */
public final class SkillRegistryException extends RuntimeException {
    private final int status;
    private final String code;

    /**
     * 创建 Skill 业务异常。
     * @param status 建议 HTTP 状态
     * @param code 稳定机器错误码
     * @param message 面向用户或模型的错误说明
     */
    public SkillRegistryException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** @return 建议 HTTP 状态。 */
    public int status() {
        return status;
    }

    /** @return 稳定机器错误码。 */
    public String code() {
        return code;
    }
}
