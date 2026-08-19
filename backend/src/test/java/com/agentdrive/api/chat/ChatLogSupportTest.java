package com.agentdrive.api.chat;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class ChatLogSupportTest {
    @Test
    void redactsProviderAndQueryCredentialsFromMessages() {
        String message = ChatLogSupport.message(new IllegalStateException(
                "provider failed?api_key=sk-secret-value&token=secret-token"));

        assertThat(message).contains("[REDACTED]");
        assertThat(message).doesNotContain("sk-secret-value", "secret-token");
    }

    @Test
    void keepsStackFramesWithoutOriginalExceptionMessageOrCause() {
        IllegalStateException source = new IllegalStateException("api_key=secret-value",
                new RuntimeException("nested-secret"));
        Throwable safe = ChatLogSupport.safeThrowable(source);
        StringWriter output = new StringWriter();
        safe.printStackTrace(new PrintWriter(output));

        assertThat(output.toString()).contains("ChatLogSupportTest");
        assertThat(output.toString()).doesNotContain("secret-value", "nested-secret");
    }
}
