package com.agentdrive.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 只在 Identity Service 内把明文 credential 转为 SHA-256 摘要。 */
public final class CredentialHash {
    private CredentialHash() {
    }

    /** 对 token 生成固定长度十六进制 SHA-256 摘要。 */
    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
