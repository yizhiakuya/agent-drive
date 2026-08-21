package com.agentdrive.vision;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VisionConfigurationServiceTest {
    @Test
    void revealsSavedKeyForAuthenticatedSettingsEndpoint() {
        UUID owner = UUID.randomUUID();
        byte[] encrypted = {1, 2, 3};
        VisionConfigStore configs = mock(VisionConfigStore.class);
        VisionSecretCipher keyCipher = mock(VisionSecretCipher.class);
        when(configs.find(owner)).thenReturn(Optional.of(new VisionConfigStore.VisionConfig(
                "openai_compat", "https://vision.example/v1", "vision-model", encrypted)));
        when(keyCipher.decrypt(encrypted)).thenReturn("vision-secret");

        Optional<String> result = new VisionConfigurationService(
                configs, keyCipher, mock(VisionModelClient.class)).revealApiKey(owner);

        assertThat(result).contains("vision-secret");
    }

    @Test
    void reusesSavedKeyWhenOnlyModelChanges() {
        UUID owner = UUID.randomUUID();
        byte[] encrypted = {1, 2, 3};
        byte[] reencrypted = {4, 5, 6};
        VisionConfigStore configs = mock(VisionConfigStore.class);
        VisionSecretCipher keyCipher = mock(VisionSecretCipher.class);
        VisionModelClient client = mock(VisionModelClient.class);
        when(configs.find(owner)).thenReturn(Optional.of(new VisionConfigStore.VisionConfig(
                "openai_compat", "https://vision.example/v1", "old-model", encrypted)));
        when(keyCipher.decrypt(encrypted)).thenReturn("saved-secret");
        when(keyCipher.encrypt("saved-secret")).thenReturn(reencrypted);
        when(client.test(any())).thenReturn(Map.of("ok", true));

        Map<String, Object> result = new VisionConfigurationService(configs, keyCipher, client)
                .save(owner, "openai_compat", "https://vision.example/v1/", "", "new-model");

        assertThat(result).containsEntry("ok", true);
        ArgumentCaptor<VisionRuntimeConfig.Config> runtime = ArgumentCaptor.forClass(VisionRuntimeConfig.Config.class);
        verify(client).test(runtime.capture());
        assertThat(runtime.getValue()).isEqualTo(new VisionRuntimeConfig.Config(
                "openai_compat", "https://vision.example/v1", "new-model", "saved-secret"));

        ArgumentCaptor<VisionConfigStore.VisionConfig> saved = ArgumentCaptor.forClass(VisionConfigStore.VisionConfig.class);
        verify(configs).save(org.mockito.ArgumentMatchers.eq(owner), saved.capture());
        assertThat(saved.getValue().model()).isEqualTo("new-model");
        assertThat(saved.getValue().encryptedApiKey()).isSameAs(reencrypted);
    }

    @Test
    void requiresNewKeyWhenBaseUrlChanges() {
        UUID owner = UUID.randomUUID();
        byte[] encrypted = {1, 2, 3};
        VisionConfigStore configs = mock(VisionConfigStore.class);
        VisionSecretCipher keyCipher = mock(VisionSecretCipher.class);
        VisionModelClient client = mock(VisionModelClient.class);
        when(configs.find(owner)).thenReturn(Optional.of(new VisionConfigStore.VisionConfig(
                "openai_compat", "https://old.example/v1", "old-model", encrypted)));

        VisionConfigurationService service = new VisionConfigurationService(configs, keyCipher, client);

        assertThatThrownBy(() -> service.save(
                owner, "openai_compat", "https://new.example/v1", "", "new-model"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("视觉模型 API Key 不能为空");
        verify(keyCipher, never()).decrypt(any());
        verify(configs, never()).save(any(), any());
        verifyNoInteractions(client);
    }
}
