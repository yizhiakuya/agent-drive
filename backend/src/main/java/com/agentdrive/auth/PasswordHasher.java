package com.agentdrive.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 使用 PBKDF2-HMAC-SHA256 处理 owner 密码。
 *
 * <p>新密码使用 16 字节随机 salt、600,000 次迭代和 256 位派生值，编码格式为
 * {@code pbkdf2$iterations$saltHex$derivedHex}。验证会限制迭代次数和字段长度，
 * 并使用常量时间比较；密码明文不会离开方法调用。</p>
 */
public final class PasswordHasher {
    public static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 为密码生成随机 salt 并返回 PBKDF2 编码串。
     * @param password 8 至 128 字符的明文密码
     * @return 含算法、迭代次数、salt 和派生值的存储字符串
     * @throws IllegalArgumentException 密码长度不合法时抛出
     */
    public String hash(String password) {
        validate(password);
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return encode(password, salt, ITERATIONS);
    }

    /**
     * 验证密码是否匹配受支持的 PBKDF2 编码串。
     *
     * <p>格式错误、参数超出 100,000 至 2,000,000 次迭代范围和派生值不匹配都返回
     * false，不向调用方泄露具体失败原因。</p>
     * @param password 待验证的明文密码
     * @param encoded 数据库存储的 PBKDF2 编码串
     * @return 密码匹配时为 true，否则为 false
     */
    public boolean matches(String password, String encoded) {
        if (password == null || encoded == null || encoded.isBlank()) {
            return false;
        }
        try {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 4 || !"pbkdf2".equals(parts[0])) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 100_000 || iterations > 2_000_000
                    || parts[2].length() != SALT_BYTES * 2
                    || parts[3].length() != KEY_BITS / 4) {
                return false;
            }
            byte[] salt = HexFormat.of().parseHex(parts[2]);
            byte[] expected = HexFormat.of().parseHex(parts[3]);
            byte[] actual = derive(password, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    /**
     * 将 PBKDF2 派生值和 salt 编码为持久化格式。
     * @param password 明文密码
     * @param salt 派生使用的随机 salt
     * @param iterations PBKDF2 迭代次数
     * @return {@code pbkdf2$iterations$saltHex$derivedHex}
     */
    private String encode(String password, byte[] salt, int iterations) {
        return "pbkdf2$" + iterations + "$" + HexFormat.of().formatHex(salt)
                + "$" + HexFormat.of().formatHex(derive(password, salt, iterations));
    }

    /**
     * 使用 PBKDF2-HMAC-SHA256 派生 256 位密码值，并在返回前清理 JCE 密码规格中的
     * char[]。
     * @param password 明文密码
     * @param salt 16 字节 salt
     * @param iterations 迭代次数
     * @return 32 字节派生值
     * @throws IllegalStateException JCE 算法不可用时抛出
     */
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
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is unavailable", error);
        }
    }

    /**
     * 校验密码的长度策略。
     * @param password 待设置的明文密码
     * @throws IllegalArgumentException 密码为空、短于 8 或长于 128 字符时抛出
     */
    private void validate(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("password must be 8-128 characters");
        }
    }
}
