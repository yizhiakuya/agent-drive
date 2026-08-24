package com.agentdrive.identity;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** 使用与当前 API 相同格式的 PBKDF2-HMAC-SHA256 owner 密码哈希器。 */
@Component
public final class PasswordHasher {
    private static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 生成带随机 salt 的密码哈希。 */
    public String hash(String password) {
        validate(password);
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return encode(password, salt, ITERATIONS);
    }

    /** 验证密码哈希，格式或参数错误统一返回 false。 */
    public boolean matches(String password, String encoded) {
        if (password == null || encoded == null || encoded.isBlank()) return false;
        try {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 4 || !"pbkdf2".equals(parts[0])) return false;
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 100_000 || iterations > 2_000_000
                    || parts[2].length() != SALT_BYTES * 2 || parts[3].length() != KEY_BITS / 4) {
                return false;
            }
            byte[] expected = HexFormat.of().parseHex(parts[3]);
            byte[] actual = derive(password, HexFormat.of().parseHex(parts[2]), iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private String encode(String password, byte[] salt, int iterations) {
        return "pbkdf2$" + iterations + "$" + HexFormat.of().formatHex(salt)
                + "$" + HexFormat.of().formatHex(derive(password, salt, iterations));
    }

    private byte[] derive(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (Exception error) {
            throw new IllegalStateException("PBKDF2-HMAC-SHA256 is unavailable", error);
        }
    }

    private void validate(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("password must be 8-128 characters");
        }
    }
}
