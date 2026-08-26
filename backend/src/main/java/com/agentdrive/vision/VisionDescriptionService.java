package com.agentdrive.vision;

import com.agentdrive.files.FileContentPort;
import com.agentdrive.files.FileStorageException;

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
import java.util.function.Consumer;

/**
 * 负责读取 owner 图片并调用已配置的视觉模型生成综合文字描述。
 *
 * <p>该服务只处理图片识别；索引 operation 调用同一服务后再把描述交给
 * 全文/chunk/embedding 链路，保证模型调用和文件内容变更有清晰边界。</p>
 */
public final class VisionDescriptionService implements VisionDescriptionPort {
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final long MAX_BATCH_BYTES = 20L * 1024 * 1024;
    private static final int MAX_BATCH_IMAGES = 4;
    private static final Map<String, String> IMAGE_TYPES = Map.of(
            ".png", "image/png",
            ".jpg", "image/jpeg",
            ".jpeg", "image/jpeg",
            ".gif", "image/gif",
            ".webp", "image/webp",
            ".bmp", "image/bmp"
    );

    private final VisionRuntimeConfig configs;
    private final FileContentPort files;
    private final VisionModelClient client;

    private record PreparedImage(String path, String mediaType, byte[] bytes, String imageId) {
    }

    /**
     * 创建图片描述服务。
     * @param configs 按 owner 读取视觉模型配置的运行时端口。
     * @param files 执行路径安全校验和文件读取的存储服务。
     * @param client 调用 OpenAI 兼容视觉模型的客户端。
     */
    public VisionDescriptionService(VisionRuntimeConfig configs, FileContentPort files, VisionModelClient client) {
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
        return describeFiles(userId, paths, null);
    }

    @Override
    public Map<String, Object> describeFiles(UUID userId, List<String> paths,
                                              Consumer<Map<String, Object>> progressListener) {
        Optional<VisionRuntimeConfig.Config> config = configs.find(userId);
        if (config.isEmpty() || config.get().apiKey() == null || config.get().apiKey().isBlank()) {
            return Map.of("ok", false, "error", "vision_not_configured", "items", List.of());
        }
        List<Map<String, Object>> items = describeInBatches(userId, paths, config.get(), progressListener);
        boolean anySuccess = items.stream().anyMatch(item -> item.get("description") instanceof String description
                && !description.isBlank());
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
     * Reads images once per explicit request and sends small independent multi-image batches.
     * No previous description is reused; each call may intentionally regenerate all descriptions.
     */
    private List<Map<String, Object>> describeInBatches(UUID userId, List<String> paths,
                                                         VisionRuntimeConfig.Config config,
                                                         Consumer<Map<String, Object>> progressListener) {
        List<Map<String, Object>> items = new ArrayList<>();
        List<PreparedImage> pending = new ArrayList<>();
        long pendingBytes = 0;
        int sequence = 0;
        for (String path : paths) {
            try {
                PreparedImage image = prepareImage(userId, path, "image-" + sequence++);
                if (!pending.isEmpty() && (pending.size() >= MAX_BATCH_IMAGES
                        || pendingBytes + image.bytes().length > MAX_BATCH_BYTES)) {
                    appendBatch(items, pending, config);
                    reportProgress(progressListener, items, paths.size());
                    pending = new ArrayList<>();
                    pendingBytes = 0;
                }
                pending.add(image);
                pendingBytes += image.bytes().length;
            } catch (Exception error) {
                items.add(failure(path, error));
            }
        }
        if (!pending.isEmpty()) {
            appendBatch(items, pending, config);
            reportProgress(progressListener, items, paths.size());
        }
        return items;
    }

    private void reportProgress(Consumer<Map<String, Object>> listener,
                                List<Map<String, Object>> items, int total) {
        if (listener == null) return;
        int succeeded = (int) items.stream().filter(item -> item.get("description") instanceof String text
                && !text.isBlank()).count();
        listener.accept(Map.of(
                "phase", "vision",
                "message", "正在调用视觉模型分析图片",
                "completed", items.size(),
                "total", Math.max(0, total),
                "succeeded", succeeded,
                "failed", Math.max(0, items.size() - succeeded)
        ));
    }

    /** Calls the batch endpoint and falls back to independent calls on provider protocol failure. */
    private void appendBatch(List<Map<String, Object>> items, List<PreparedImage> batch,
                             VisionRuntimeConfig.Config config) {
        try {
            List<VisionModelClient.ImageInput> inputs = batch.stream()
                    .map(image -> new VisionModelClient.ImageInput(image.imageId(), image.bytes(), image.mediaType()))
                    .toList();
            Map<String, String> descriptions = client.describeBatch(config, inputs);
            for (PreparedImage image : batch) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("path", image.path());
                item.put("mime_type", image.mediaType());
                item.put("model", config.model());
                item.put("description", descriptions.get(image.imageId()));
                items.add(item);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            for (PreparedImage image : batch) {
                items.add(failure(image.path(), new IllegalStateException("vision_request_interrupted")));
            }
        } catch (Exception batchError) {
            // A provider may claim multimodal support but reject multi-image content; preserve
            // per-file semantics by retrying the failed batch as single-image requests.
            for (PreparedImage image : batch) {
                try {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("path", image.path());
                    item.put("mime_type", image.mediaType());
                    item.put("model", config.model());
                    item.put("description", client.describe(config, image.bytes(), image.mediaType(), image.path()));
                    items.add(item);
                } catch (Exception error) {
                    items.add(failure(image.path(), error));
                }
            }
        }
    }

    private PreparedImage prepareImage(UUID userId, String path, String imageId) throws IOException {
        String mediaType = IMAGE_TYPES.get(extension(path));
        if (mediaType == null) throw new IllegalArgumentException("unsupported_image_type");
        byte[] bytes = files.readBytes(userId, path, MAX_IMAGE_BYTES);
        return new PreparedImage(path, mediaType, bytes, imageId);
    }

    private Map<String, Object> failure(String path, Exception error) {
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("path", path);
        failed.put("ok", false);
        failed.put("error", safeMessage(error));
        return failed;
    }

    /**
     * 在批量视觉 operation 开始前验证当前视觉配置和 provider 路由，避免执行必然失败的请求。
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
     * 识别单个图片并返回可供索引链路复用的综合描述结果。
     * @param userId 图片归属 owner UUID。
     * @param path 图片相对路径。
     * @return path、MIME、模型和综合文字 description。
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
        try {
            PreparedImage prepared = prepareImage(userId, path, "image-0");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", path);
            result.put("mime_type", prepared.mediaType());
            result.put("model", config.model());
            result.put("description", client.describe(config, prepared.bytes(), prepared.mediaType(), path));
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
     * @return 面向索引 operation 和 API 的错误标识。
     */
    private static String safeMessage(Exception error) {
        if (error instanceof FileStorageException storage) return storage.getMessage();
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
