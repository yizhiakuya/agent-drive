package com.agentdrive.vision;

/**
 * 视觉配置使用的密钥保护端口。
 *
 * <p>视觉模块只知道如何请求密钥保护，不依赖具体的 AES、JCE 或环境变量实现；实现类必须
 * 保证明文只在进程内短暂存在，不能把 key 写入日志或任务结果。</p>
 */
public interface VisionSecretCipher {
    /**
     * 加密视觉模型 API key。
     *
     * @param apiKey 待加密的明文 API key。
     * @return 可持久化的密文字节；空 key 可返回 {@code null}。
     */
    byte[] encrypt(String apiKey);

    /**
     * 解密已保存的视觉模型 API key。
     *
     * @param encryptedApiKey 持久化的密文字节。
     * @return 进程内使用的明文 API key；空密文返回空字符串。
     */
    String decrypt(byte[] encryptedApiKey);
}
