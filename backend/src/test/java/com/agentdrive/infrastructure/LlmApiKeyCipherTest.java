package com.agentdrive.infrastructure;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmApiKeyCipherTest {
    @Test
    void encryptsAndDecryptsWithoutStoringPlaintext() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        LlmApiKeyCipher cipher = new LlmApiKeyCipher(key);

        byte[] stored = cipher.encrypt("sk-test-secret");

        assertThat(stored).isNotNull();
        assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("sk-test-secret");
        assertThat(cipher.decrypt(stored)).isEqualTo("sk-test-secret");
        assertThat(cipher.encrypt("")).isNull();
    }

    @Test
    void rejectsTamperedCiphertext() {
        LlmApiKeyCipher cipher = new LlmApiKeyCipher(new byte[32]);
        byte[] stored = cipher.encrypt("secret");
        stored[stored.length - 1] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt(stored))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be decrypted");
    }
}
