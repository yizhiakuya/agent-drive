package com.agentdrive.api.chat;

import com.agentdrive.agent.BackendApiRequest;
import com.agentdrive.api.config.ProviderConfigController;
import com.agentdrive.api.config.VisionConfigController;
import com.agentdrive.infrastructure.EmbeddingConfigStore;
import com.agentdrive.infrastructure.LlmApiKeyCipher;
import com.agentdrive.infrastructure.LlmProviderConfigService;
import com.agentdrive.vision.VisionDescriptionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatBackendApiConfigHandlerTest {
    @Test
    void agentVisionCallsReuseSavedBaseUrlWhenOmitted() {
        UUID owner = UUID.randomUUID();
        ProviderConfigController providerConfig = mock(ProviderConfigController.class);
        VisionConfigController visionConfig = mock(VisionConfigController.class);
        VisionDescriptionService vision = mock(VisionDescriptionService.class);
        LlmProviderConfigService configs = mock(LlmProviderConfigService.class);
        EmbeddingConfigStore embeddingConfigs = mock(EmbeddingConfigStore.class);
        LlmApiKeyCipher keyCipher = mock(LlmApiKeyCipher.class);
        String savedBaseUrl = "https://vision.example/v1";
        when(visionConfig.currentForOwner(owner)).thenReturn(Map.of(
                "configured", true,
                "provider", "openai_compat",
                "base_url", savedBaseUrl,
                "model", "old-model",
                "api_key_masked", "secret…"
        ));
        Map<String, Object> models = Map.of("ok", true, "models", List.of("new-model"));
        Map<String, Object> saved = Map.of("ok", true, "saved", Map.of("model", "new-model"));
        when(visionConfig.modelsForOwner(owner, "openai_compat", savedBaseUrl, "")).thenReturn(models);
        when(visionConfig.saveForOwner(owner, "openai_compat", savedBaseUrl, "", "new-model"))
                .thenReturn(saved);

        ChatBackendApiConfigHandler handler = new ChatBackendApiConfigHandler(
                providerConfig, visionConfig, vision, configs, embeddingConfigs, keyCipher);
        Map<String, Object> modelResult = handler.dispatch(
                "POST /api/v1/config/vision/models",
                new BackendApiRequest("call", null, "POST /api/v1/config/vision/models", null,
                        null, Map.of(), null),
                owner);
        Map<String, Object> saveResult = handler.dispatch(
                "PUT /api/v1/config/vision",
                new BackendApiRequest("call", null, "PUT /api/v1/config/vision", null,
                        null, Map.of("model", "new-model"), null),
                owner);

        assertThat(modelResult).isSameAs(models);
        assertThat(saveResult).isSameAs(saved);
        verify(visionConfig).modelsForOwner(eq(owner), eq("openai_compat"), eq(savedBaseUrl), eq(""));
        verify(visionConfig).saveForOwner(eq(owner), eq("openai_compat"), eq(savedBaseUrl), eq(""), eq("new-model"));
    }

    @Test
    void agentVisionCallKeepsExplicitBaseUrl() {
        UUID owner = UUID.randomUUID();
        ProviderConfigController providerConfig = mock(ProviderConfigController.class);
        VisionConfigController visionConfig = mock(VisionConfigController.class);
        VisionDescriptionService vision = mock(VisionDescriptionService.class);
        LlmProviderConfigService configs = mock(LlmProviderConfigService.class);
        EmbeddingConfigStore embeddingConfigs = mock(EmbeddingConfigStore.class);
        LlmApiKeyCipher keyCipher = mock(LlmApiKeyCipher.class);
        String explicitBaseUrl = "https://other.example/v1";
        Map<String, Object> models = Map.of("ok", true, "models", List.of("model"));
        when(visionConfig.modelsForOwner(owner, "openai_compat", explicitBaseUrl, ""))
                .thenReturn(models);

        ChatBackendApiConfigHandler handler = new ChatBackendApiConfigHandler(
                providerConfig, visionConfig, vision, configs, embeddingConfigs, keyCipher);
        Map<String, Object> result = handler.dispatch(
                "POST /api/v1/config/vision/models",
                new BackendApiRequest("call", null, "POST /api/v1/config/vision/models", null,
                        null, Map.of("base_url", explicitBaseUrl), null),
                owner);

        assertThat(result).isSameAs(models);
        verify(visionConfig).modelsForOwner(eq(owner), eq("openai_compat"), eq(explicitBaseUrl), eq(""));
    }
}
