package com.agentdrive.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataRedactorTest {
    @Test
    void redactsProviderTokensAndAuthorizationText() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor();

        String result = redactor.text("sk-abcdefgh1234 jina_abcdefgh1234 Bearer abcdefgh1234");

        assertThat(result).doesNotContain("sk-abcdefgh1234", "jina_abcdefgh1234", "Bearer abcdefgh1234");
        assertThat(result).contains("[REDACTED]");
    }

    @Test
    void redactsSecretKeysRecursively() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor();

        Object result = redactor.value(Map.of(
                "api_key", "secret",
                "nested", List.of(Map.of("token", "device-secret", "value", "visible"))
        ));

        assertThat(result).isEqualTo(Map.of(
                "api_key", "***",
                "nested", List.of(Map.of("token", "***", "value", "visible"))
        ));
    }
}
