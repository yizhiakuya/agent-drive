package com.agentdrive.infrastructure;

import java.util.Base64;

/**
 * 解析启动配置中的固定长度密钥材料。
 * <p>该类不保存密钥；它只把 Base64 配置解码为 32 字节，并在配置缺失、编码错误或长度不符时失败。</p>
 */
public final class SecretMaterial {
    /**
     * 阻止实例化纯静态工具类。
     */
    private SecretMaterial() {
    }

    /**
     * 解码并校验一个启动密钥。
     * @param encoded 待解码的 Base64 文本，首尾空白会被忽略。
     * @param name 用于异常消息的配置项名称。
     * @return 恰好 32 字节的解码结果。
     * @throws IllegalArgumentException 配置为空、不是 Base64 或解码后不是 32 字节时抛出。
     */
    public static byte[] base64Key(String encoded, String name) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException(name + " must be configured");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(name + " must be base64", error);
        }
        if (decoded.length != 32) {
            throw new IllegalArgumentException(name + " must decode to 32 bytes");
        }
        return decoded;
    }
}
