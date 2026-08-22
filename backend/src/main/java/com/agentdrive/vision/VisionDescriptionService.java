package com.agentdrive.vision;

import com.agentdrive.files.FileStorageException;
import com.agentdrive.files.FileStorageService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 负责读取 owner 图片并调用已配置的视觉模型生成结构化描述。
 *
 * <p>该服务只处理图片识别，不在 HTTP 请求中写入索引；批量向量化由 Worker 调用同一服务
 * 后再把描述交给全文/chunk/embedding 链路，保证模型调用和文件内容变更有清晰边界。</p>
 */
@Service
@Profile({"java-files", "java-auth", "java-chat"})
public final class VisionDescriptionService {
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final Map<String, String> IMAGE_TYPES = Map.of(
            ".png", "image/png",
            ".jpg", "image/jpeg",
            ".jpeg", "image/jpeg",
            ".gif", "image/gif",
            ".webp", "image/webp",
            ".bmp", "image/bmp"
    );

    private final VisionRuntimeConfig configs;
    private final FileStorageService files;
    private final VisionModelClient client;

    /**
     * 创建图片描述服务。
     * @param configs 按 owner 读取视觉模型配置的运行时端口。
     * @param files 执行路径安全校验和文件读取的存储服务。
     * @param client 调用 OpenAI 兼容视觉模型的客户端。
     */
    public VisionDescriptionService(VisionRuntimeConfig configs, FileStorageService files, VisionModelClient client) {
        this.configs = configs;
        this.files = files;
        this.client = client;
    }

    /**
     * 批量识别图片；每个条目独立返回，单个图片失败不会吞掉其他图片结果。
     * @param userId 图片归属 owner UUID。
     * @param paths owner 根目录下的图片相对路径列表。
     * @return items 数组，每项包含 path、mime_type、model 和 description，失败项带 error。
     */
    public Map<String, Object> describeFiles(UUID userId, List<String> paths) {
        Optional<VisionRuntimeConfig.Config> config = configs.find(userId);
        if (config.isEmpty() || config.get().apiKey() == null || config.get().apiKey().isBlank()) {
            return Map.of("ok", false, "error", "vision_not_configured", "items", List.of());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (String path : paths) {
            try {
                items.add(describeFile(userId, path, config.get()));
            } catch (Exception error) {
                Map<String, Object> failed = new LinkedHashMap<>();
                failed.put("path", path);
                failed.put("ok", false);
                failed.put("error", safeMessage(error));
                items.add(failed);
            }
        }
        boolean anySuccess = items.stream().anyMatch(item -> Boolean.TRUE.equals(item.get("description"))
                || item.containsKey("mime_type"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", anySuccess);
        result.put("model", config.get().model());
        result.put("items", List.copyOf(items));
        if (!anySuccess) {
            result.put("error", "vision_all_files_failed");
        }
        return Map.copyOf(result);
    }

    /**
     * 在批量任务入队前验证当前视觉配置和 provider 路由，避免把必然失败的任务放入队列重试。
     * @param userId 当前 owner
     * @return ready、model 和 provider 的安全诊断
     * @throws VisionProviderUnavailableException 配置缺失或探测失败
     */
    public Map<String, Object> requireReady(UUID userId) {
        Optional<VisionRuntimeConfig.Config> config = configs.find(userId);
        if (config.isEmpty() || config.get().apiKey() == null || config.get().apiKey().isBlank()) {
            throw new VisionProviderUnavailableException("vision_not_configured: 请先配置视觉模型和 API Key");
        }
        Map<String, Object> probe = client.test(config.get());
        if (!Boolean.TRUE.equals(probe.get("ok"))) {
            String detail = String.valueOf(probe.getOrDefault("error", "vision provider unavailable"));
            throw new VisionProviderUnavailableException("vision_provider_unavailable: " + detail);
        }
        return Map.of("ready", true, "model", config.get().model());
    }

    /**
     * 识别单个图片并返回可供索引任务复用的结构化结果。
     * @param userId 图片归属 owner UUID。
     * @param path 图片相对路径。
     * @return path、MIME、模型和结构化 description。
     */
    public Map<String, Object> describeFile(UUID userId, String path) {
        VisionRuntimeConfig.Config config = configs.find(userId)
                .orElseThrow(() -> new IllegalStateException("vision_not_configured"));
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException("vision_not_configured");
        }
        return describeFile(userId, path, config);
    }

    /**
     * 判断路径是否为视觉服务支持的图片类型。
     * @param path owner 相对路径。
     * @return 扩展名属于受支持图片格式时为 true。
     */
    public boolean isImage(String path) {
        return IMAGE_TYPES.containsKey(extension(path));
    }

    /**
     * 读取、校验并调用视觉模型。
     * @param userId 图片归属 owner UUID。
     * @param path 图片相对路径。
     * @param config 已解析的视觉配置。
     * @return 图片描述结果。
     */
    private Map<String, Object> describeFile(UUID userId, String path, VisionRuntimeConfig.Config config) {
        String mediaType = IMAGE_TYPES.get(extension(path));
        if (mediaType == null) throw new IllegalArgumentException("unsupported_image_type");
        Path file = files.fileForRead(userId, path);
        try {
            long size = Files.size(file);
            if (size > MAX_IMAGE_BYTES) throw new IllegalArgumentException("image_too_large");
            byte[] image = Files.readAllBytes(file);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", path);
            result.put("mime_type", mediaType);
            result.put("model", config.model());
            result.put("description", client.describe(config, image, mediaType, path));
            return result;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("vision_request_failed", error);
        }
    }

    /**
     * 提取小写扩展名。
     * @param path 图片相对路径。
     * @return 最后一个点及其后的扩展名。
     */
    private String extension(String path) {
        String name = path == null ? "" : path.toLowerCase(Locale.ROOT);
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index);
    }

    /**
     * 生成不暴露 provider body 的稳定错误文本。
     * @param error 识别异常。
     * @return 面向任务和 API 的错误标识。
     */
    private static String safeMessage(Exception error) {
        if (error instanceof FileStorageException storage) return storage.getMessage();
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
