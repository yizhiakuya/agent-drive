package com.agentdrive.vision;

/** 视觉 provider 在业务执行前置检查中不可用，调用方应修正配置后重试。 */
public final class VisionProviderUnavailableException extends RuntimeException {
    public VisionProviderUnavailableException(String message) {
        super(message);
    }
}
