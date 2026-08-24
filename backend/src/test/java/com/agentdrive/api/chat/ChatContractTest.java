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
        assertThat(request.permissionMode()).isEqualTo("auto");
        assertThat(request.inlineImages()).isEmpty();
    }

    @Test
    void requestNormalizesSelectedModelWithoutChangingProviderFields() {
        ChatRequest request = new ChatRequest(
                "hello", null, null, null, null, null, null, null, "  fast-model  ");

        assertThat(request.model()).isEqualTo("fast-model");
    }

    @Test
    void requestAcceptsPermissionModeAndKeepsItAcrossServerCopies() {
        ChatRequest request = new ChatRequest(
                "hello", null, null, "00000000-0000-0000-0000-000000000001", "auto",
                null, null, null, "", null, "ask");

        assertThat(request.permissionMode()).isEqualTo("ask");
        assertThat(request.withSessionId(request.sessionId()).permissionMode()).isEqualTo("ask");
        assertThat(request.withAuthenticatedUserId(null).permissionMode()).isEqualTo("ask");
        assertThat(request.withRequestId("request").permissionMode()).isEqualTo("ask");
    }

    @Test
    void acceptsBase64InlineImageAndKeepsItAcrossServerCopies() {
        ChatRequest.InlineImage image = new ChatRequest.InlineImage("paste.png", "image/png", "aGVsbG8=");
        ChatRequest request = new ChatRequest(
                "describe", null, null, "00000000-0000-0000-0000-000000000001", "auto",
                null, null, null, "gpt-5.6-luna", null, "auto", java.util.List.of(image));

        assertThat(request.inlineImages()).containsExactly(image);
        assertThat(request.inlineImagesValid()).isTrue();
        assertThat(request.withSessionId(request.sessionId()).inlineImages()).containsExactly(image);
        assertThat(request.withRequestId("request").inlineImages()).containsExactly(image);
    }

    @Test
    void inlineImageBudgetCoversFiftyMiBRawImageAfterBase64Encoding() {
        long rawBytes = 50L * 1024 * 1024;
        long base64Chars = ((rawBytes + 2) / 3) * 4;

        assertThat((long) ChatRequest.MAX_INLINE_IMAGE_BASE64_CHARS).isGreaterThanOrEqualTo(base64Chars);
        assertThat((long) ChatRequest.MAX_INLINE_IMAGE_TOTAL_BASE64_CHARS).isGreaterThanOrEqualTo(base64Chars);
        assertThat((long) ChatRequest.MAX_BODY_BYTES).isGreaterThan(base64Chars);
    }

    @Test
    void rejectsInvalidInlineImagePayload() {
        ChatRequest request = new ChatRequest(
                "describe", null, null, null, "auto", null, null, null, "gpt-5.6-luna", null,
                "auto", java.util.List.of(new ChatRequest.InlineImage("x", "text/plain", "not image")));

        assertThat(request.inlineImagesValid()).isFalse();
    }

    @Test
    void fileContextOnlyAcceptsOwnerRelativePosixPaths() {
        ChatRequest request = new ChatRequest(
                "hello", null, null, null, null, null, null, null, "",
                java.util.List.of("docs/readme.md", "../outside"));

        assertThat(request.fileContext()).containsExactly("docs/readme.md", "../outside");
        assertThat(request.fileContextPathsValid()).isFalse();
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
    void toolEventsNeverEchoCredentialFields() throws Exception {
        String encoded = encoder.encode(ChatSseEvents.toolTrace(
                1, "backend_api", Map.of("api_key", "sk-secret-value"),
                "{}", Map.of("ok", true), false, false));

        assertThat(encoded).doesNotContain("sk-secret-value").contains("\"api_key\":\"***\"");
    }

    @Test
    void contextEventKeepsSourceKindAndFullContent() throws Exception {
        String encoded = encoder.encode(ChatSseEvents.context(
                new ChatContext("skill-catalog", "skill-catalog", "catalog body", true)));

        assertThat(encoded).contains("event: context");
        assertThat(encoded).contains("\"source\":\"skill-catalog\"");
        assertThat(encoded).contains("\"kind\":\"skill-catalog\"");
        assertThat(encoded).contains("\"content\":\"catalog body\"");
        assertThat(encoded).contains("\"trust\":\"user_data\"");
    }

    @Test
    void nullEventDataIsRejected() {
        assertThatThrownBy(() -> new ChatSseEvent("text", null))
                .isInstanceOf(NullPointerException.class);
    }
}
