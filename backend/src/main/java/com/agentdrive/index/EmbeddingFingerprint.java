package com.agentdrive.index;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 计算 embedding provider、地址和模型的稳定指纹。
 * API key 不参与指纹，因为换密钥不会改变已有向量的语义；切换 provider、地址或模型才会使向量失效。
 */
public final class EmbeddingFingerprint {
    private EmbeddingFingerprint() {
    }

    /**
     * 计算当前 embedding 配置指纹。
     * @param provider provider 名称。
     * @param baseUrl provider 基础地址。
     * @param model embedding 模型名。
     * @return 64 位小写 SHA-256 十六进制指纹。
     */
    public static String of(String provider, String baseUrl, String model) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (String.valueOf(provider) + "|" + String.valueOf(baseUrl) + "|" + String.valueOf(model))
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("cannot fingerprint embedding configuration", error);
        }
    }
}
