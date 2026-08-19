package com.agentdrive.infrastructure;

import com.agentdrive.vision.VisionSecretCipher;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 使用 AES-GCM 保护 LLM provider 的 API key。
 * <p>密文格式为 12 字节随机 nonce 后接 GCM 密文和认证标签；密钥只在构造时复制，
 * 加解密过程会清理临时 nonce 和密文字节。</p>
 */
public final class LlmApiKeyCipher implements VisionSecretCipher {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    /**
     * 校验并复制 AES 密钥。
     * @param key 用于 GCM 加解密的 16、24 或 32 字节密钥；不会保存调用方数组本身。
     * @throws IllegalArgumentException key 为空或长度不是 AES 支持的长度时抛出。
     */
    public LlmApiKeyCipher(byte[] key) {
        if (key == null || (key.length != 16 && key.length != 24 && key.length != 32)) {
            throw new IllegalArgumentException("LLM key encryption key must be 16, 24, or 32 bytes");
        }
        this.key = key.clone();
    }

    /**
     * 为 API key 生成随机 nonce 并执行 AES-GCM 加密。
     * @param apiKey 要保护的明文 API key；{@code null} 或空字符串表示未配置并返回 {@code null}。
     * @return nonce、密文和认证标签拼接后的持久化字节数组。
     * @throws IllegalStateException JCE 无法创建或执行 AES-GCM 时抛出。
     */
    public byte[] encrypt(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return null;
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce);
            byte[] encrypted = cipher.doFinal(apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] result = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, result, 0, nonce.length);
            System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
            return result;
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("unable to encrypt LLM API key", error);
        } finally {
            Arrays.fill(nonce, (byte) 0);
        }
    }

    /**
     * 按“前 12 字节 nonce、剩余部分密文”的格式解密 API key。
     * @param stored 数据库中读取的密文；空值或空数组表示未配置，返回空字符串。
     * @return 解密得到的 UTF-8 API key。
     * @throws IllegalArgumentException 密文缺少 nonce、认证失败或无法解密时抛出。
     */
    public String decrypt(byte[] stored) {
        if (stored == null || stored.length == 0) {
            return "";
        }
        if (stored.length <= NONCE_BYTES) {
            throw new IllegalArgumentException("encrypted LLM API key is malformed");
        }
        byte[] nonce = Arrays.copyOf(stored, NONCE_BYTES);
        byte[] encrypted = Arrays.copyOfRange(stored, NONCE_BYTES, stored.length);
        try {
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, nonce);
            return new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
        } catch (GeneralSecurityException error) {
            throw new IllegalArgumentException("encrypted LLM API key cannot be decrypted", error);
        } finally {
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(encrypted, (byte) 0);
        }
    }

    /**
     * 创建绑定当前密钥和 nonce 的 AES/GCM cipher。
     * @param mode {@link Cipher#ENCRYPT_MODE} 或 {@link Cipher#DECRYPT_MODE}。
     * @param nonce 当前密文携带的 12 字节 GCM nonce。
     * @return 已初始化的 JCE cipher。
     * @throws GeneralSecurityException JCE 不支持 AES/GCM 或初始化参数无效时抛出。
     */
    private Cipher cipher(int mode, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        return cipher;
    }
}
