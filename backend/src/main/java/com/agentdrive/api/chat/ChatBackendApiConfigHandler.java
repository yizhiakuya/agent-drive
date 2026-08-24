package com.agentdrive.api.chat;

import com.agentdrive.agent.BackendApiRequest;
import com.agentdrive.agent.ChatModelCapabilities;
import com.agentdrive.api.config.ProviderConfigController;
import com.agentdrive.api.config.VisionConfigController;
import com.agentdrive.infrastructure.EmbeddingConfigStore;
import com.agentdrive.infrastructure.LlmApiKeyCipher;
import com.agentdrive.infrastructure.LlmProviderConfigService;
import com.agentdrive.infrastructure.LlmProviderConfigView;
import com.agentdrive.vision.VisionDescriptionPort;
import com.agentdrive.index.IndexPaths;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
@Profile("java-chat")
final class ChatBackendApiConfigHandler implements BackendApiOperationHandler {
    private static final Set<String> OPERATIONS = Set.of(
            "GET /api/v1/config",
            "GET /api/v1/config/status",
            "GET /api/v1/config/vision",
            "POST /api/v1/vision/describe"
    );

    private final ProviderConfigController providerConfig;
    private final VisionConfigController visionConfig;
    private final VisionDescriptionPort vision;
    private final LlmProviderConfigService configs;
    private final EmbeddingConfigStore embeddingConfigs;
    private final LlmApiKeyCipher keyCipher;

    ChatBackendApiConfigHandler(ProviderConfigController providerConfig,
                                VisionConfigController visionConfig,
                                VisionDescriptionPort vision,
                                LlmProviderConfigService configs,
                                EmbeddingConfigStore embeddingConfigs,
                                LlmApiKeyCipher keyCipher) {
        this.providerConfig = providerConfig;
        this.visionConfig = visionConfig;
        this.vision = vision;
        this.configs = configs;
        this.embeddingConfigs = embeddingConfigs;
        this.keyCipher = keyCipher;
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public Map<String, Object> dispatch(String operation, BackendApiRequest request, UUID userId) {
        return switch (operation) {
            case "GET /api/v1/config" -> providerConfig(configs, userId);
            case "GET /api/v1/config/status" -> providerStatus(userId);
            case "GET /api/v1/config/vision" -> visionConfig.currentForOwner(userId);
            case "POST /api/v1/vision/describe" -> describeImages(request, userId);
            default -> throw new IllegalArgumentException("Unsupported config operation: " + operation);
        };
    }

    private Map<String, Object> providerConfig(LlmProviderConfigService service, UUID userId) {
        Optional<LlmProviderConfigView> config = service.findForOwner(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", config.isPresent());
        result.put("current", config.map(view -> Map.of(
                "type", view.provider(),
                "base_url", view.baseUrl(),
                "model", view.model(),
                "supports_images", ChatModelCapabilities.supportsImages(view.provider(), view.model())
        )).orElse(null));
        return result;
    }

    private Map<String, Object> providerStatus(UUID userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", configs.findForOwner(userId).isPresent());
        Optional<EmbeddingConfigStore.EmbeddingConfig> embedding = embeddingConfigs.find(userId);
        if (embedding.isEmpty()) {
            result.put("embeddings", Map.of("configured", false));
        } else {
            byte[] encrypted = embedding.get().encryptedApiKey();
            String apiKey = encrypted == null ? "" : keyCipher.decrypt(encrypted);
            result.put("embeddings", Map.of(
                    "configured", !apiKey.isBlank(),
                    "provider", embedding.get().provider(),
                    "base_url", embedding.get().baseUrl(),
                    "model", embedding.get().model(),
                    "api_key_masked", maskApiKey(apiKey)
            ));
        }
        return result;
    }

    private Map<String, Object> describeImages(BackendApiRequest request, UUID userId) {
        Object rawFiles = request.body().get("files");
        if (!(rawFiles instanceof List<?> files) || files.isEmpty() || files.size() > 16) {
            return Map.of("ok", false, "error", "files_must_contain_1_to_16_paths");
        }
        try {
            return vision.describeFiles(userId, IndexPaths.normalize(files));
        } catch (IllegalArgumentException error) {
            return Map.of("ok", false, "error", "invalid_files", "detail", error.getMessage());
        }
    }

    private String maskApiKey(String value) {
        if (value == null || value.isBlank()) return "";
        return value.length() <= 6 ? "******" : value.substring(0, 3) + "******";
    }
}
