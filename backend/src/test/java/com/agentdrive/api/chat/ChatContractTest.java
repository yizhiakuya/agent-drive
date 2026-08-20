package com.agentdrive.api.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatContractTest {
    private final ChatSseEncoder encoder = new ChatSseEncoder(new ObjectMapper());

    @Test
    void requestDefaultsThinkingLevelAndCollections() {
        ChatRequest request = new ChatRequest("hello", null, null, null, null);

        assertThat(request.thinkingLevel()).isEqualTo("auto");
        assertThat(request.history()).isEmpty();
        assertThat(request.confirmations()).isEmpty();
        assertThat(request.model()).isEmpty();
    }

    @Test
    void requestNormalizesSelectedModelWithoutChangingProviderFields() {
        ChatRequest request = new ChatRequest(
                "hello", null, null, null, null, null, null, null, "  fast-model  ");

        assertThat(request.model()).isEqualTo("fast-model");
    }

    @Test
    void sseDataIsAlwaysAJsonObject() throws Exception {
        String encoded = encoder.encode(ChatSseEvents.reasoning("先检查范围"));

        assertThat(encoded).isEqualTo("event: reasoning\ndata: {\"text\":\"先检查范围\"}\n\n");
        assertThat(new ObjectMapper().readTree(encoded.lines().toList().get(1).substring(6)).isObject()).isTrue();
    }

    @Test
    void toolStartKeepsStructuredArguments() throws Exception {
        String encoded = encoder.encode(ChatSseEvents.toolStart("backend_api", Map.of("action", "discover")));

        assertThat(encoded).contains("event: tool_start");
        assertThat(encoded).contains("\"tool\":\"backend_api\"");
        assertThat(encoded).contains("\"action\":\"discover\"");
    }

    @Test
    void nullEventDataIsRejected() {
        assertThatThrownBy(() -> new ChatSseEvent("text", null))
                .isInstanceOf(NullPointerException.class);
    }
}
