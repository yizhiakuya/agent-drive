package com.agentdrive.agent;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThinkingLevelTest {
    @Test
    void mapsExplicitLevelsToOpenAiReasoningEffort() {
        var request = new OpenAiChatRequestFactory().create(
                List.of(UserMessage.from("hello")), List.of(), ThinkingLevel.HIGH
        );

        assertThat(request.parameters()).isInstanceOf(OpenAiChatRequestParameters.class);
        assertThat(((OpenAiChatRequestParameters) request.parameters()).reasoningEffort())
                .isEqualTo("high");
        assertThat(((OpenAiChatRequestParameters) request.parameters()).parallelToolCalls())
                .isFalse();
    }

    @Test
    void autoLeavesProviderReasoningUnset() {
        var request = new OpenAiChatRequestFactory().create(
                List.of(UserMessage.from("hello")), List.of(), ThinkingLevel.AUTO
        );

        assertThat(((OpenAiChatRequestParameters) request.parameters()).reasoningEffort()).isNull();
    }

    @Test
    void rejectsUnknownThinkingLevel() {
        assertThatThrownBy(() -> ThinkingLevel.from("extreme"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
