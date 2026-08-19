package com.agentdrive.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 认证令牌哈希工具。
 *
 * <p>使用 UTF-8 和 SHA-256 将 session、device 或 pairing 原始令牌转换为固定长度
 * 小写十六进制字符串，供持久化层查询；本类不保存或缓存原文。</p>
 */
public final class CredentialHash {
    /** 禁止实例化纯静态哈希工具。 */
    private CredentialHash() {
    }

    /**
     * 计算非空凭据的 SHA-256 十六进制哈希。
     * @param credential 不应写入日志的原始凭据
     * @return 64 个小写十六进制字符
     * @throws IllegalArgumentException credential 为空或空白时抛出
     * @throws IllegalStateException JVM 不支持 SHA-256 时抛出
     */
    public static String sha256(String credential) {
        if (credential == null || credential.isBlank()) {
            throw new IllegalArgumentException("credential must not be blank");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(credential.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
