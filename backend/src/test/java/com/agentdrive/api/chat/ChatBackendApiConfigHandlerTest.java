package com.agentdrive.api.chat;

import com.agentdrive.api.config.ProviderConfigController;
import com.agentdrive.api.config.VisionConfigController;
import com.agentdrive.infrastructure.EmbeddingConfigStore;
import com.agentdrive.infrastructure.LlmApiKeyCipher;
import com.agentdrive.infrastructure.LlmProviderConfigService;
import com.agentdrive.vision.VisionDescriptionService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChatBackendApiConfigHandlerTest {
    @Test
    void keepsCredentialBearingConfigurationOutOfAgentHandlerOperations() {
        ChatBackendApiConfigHandler handler = new ChatBackendApiConfigHandler(
                mock(ProviderConfigController.class),
                mock(VisionConfigController.class),
                mock(VisionDescriptionService.class),
                mock(LlmProviderConfigService.class),
                mock(EmbeddingConfigStore.class),
                mock(LlmApiKeyCipher.class));

        assertThat(handler.operations()).doesNotContain(
                "POST /api/v1/config",
                "POST /api/v1/config/test",
                "POST /api/v1/config/models",
                "POST /api/v1/config/vision/models",
                "PUT /api/v1/config/vision",
                "PUT /api/v1/config/embeddings");
        assertThat(handler.operations()).contains("GET /api/v1/config/status", "POST /api/v1/vision/describe");
    }
}
