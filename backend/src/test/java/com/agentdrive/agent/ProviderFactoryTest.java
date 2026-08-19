package com.agentdrive.agent;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatRequestParameters;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderFactoryTest {
    @Test
    void mapsAnthropicHighThinkingToNativeBudget() {
        var request = new AnthropicChatRequestFactory().create(
                List.of(UserMessage.from("hello")), List.of(), ThinkingLevel.HIGH
        );

        var parameters = (AnthropicChatRequestParameters) request.parameters();
        assertThat(parameters.thinkingType()).isEqualTo("enabled");
        assertThat(parameters.thinkingBudgetTokens()).isEqualTo(8192);
        assertThat(parameters.sendThinking()).isTrue();
        assertThat(parameters.returnThinking()).isTrue();
    }

    @Test
    void normalizesProviderTypeAndKeepsBaseUrlOptional() {
        var config = new LlmProviderConfig(
                LlmProviderConfig.ProviderType.from("openai_compat"),
                null,
                null,
                "local-model"
        );

        assertThat(config.type()).isEqualTo(LlmProviderConfig.ProviderType.OPENAI_COMPATIBLE);
        assertThat(config.apiKey()).isEmpty();
        assertThat(config.baseUrl()).isEmpty();
        assertThat(new StreamingModelFactory().requestFactory(config))
                .isInstanceOf(OpenAiChatRequestFactory.class);
    }
}
