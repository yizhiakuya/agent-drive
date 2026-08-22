package com.agentdrive.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 聊天模型图片输入能力的保守规则测试。 */
class ChatModelCapabilitiesTest {
    @Test
    void recognizesKnownVisionModelsAndRejectsTextOnlyModels() {
        assertThat(ChatModelCapabilities.supportsImages("anthropic", "claude-3-5-sonnet")).isTrue();
        assertThat(ChatModelCapabilities.supportsImages("anthropic", "claude-sonnet-4-20250514")).isTrue();
        assertThat(ChatModelCapabilities.supportsImages("anthropic", "claude-2.1")).isFalse();
        assertThat(ChatModelCapabilities.supportsImages("openai_compat", "claude-3-haiku")).isTrue();
        assertThat(ChatModelCapabilities.supportsImages("openai_compat", "claude-2.1")).isFalse();
        assertThat(ChatModelCapabilities.supportsImages("openai_compat", "gpt-5.6-luna")).isTrue();
        assertThat(ChatModelCapabilities.supportsImages("openai_compat", "qwen3-vl-32b")).isTrue();
        assertThat(ChatModelCapabilities.supportsImages("openai_compat", "deepseek-v3")).isFalse();
        assertThat(ChatModelCapabilities.supportsImages("openai_compat", "unknown-model")).isFalse();
    }
}
