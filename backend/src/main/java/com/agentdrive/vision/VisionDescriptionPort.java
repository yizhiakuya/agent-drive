package com.agentdrive.vision;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 图片内容理解的应用端口。
 *
 * <p>索引、Agent API 和 HTTP Controller 只依赖这个契约，不依赖本地文件读取、
 * Provider SDK 或视觉模型 HTTP 实现。未来切换为独立 Content Intelligence Service
 * 时，可替换实现为远程客户端而不改变上层业务契约。</p>
 */
public interface VisionDescriptionPort {
    /**
     * 批量生成当前 owner 图片的综合描述。
     * @param userId 图片归属 owner UUID
     * @param paths owner 根目录下的相对图片路径
     * @return 按图片返回描述或逐项错误
     */
    Map<String, Object> describeFiles(UUID userId, List<String> paths);

    /**
     * 检查当前 owner 的视觉配置和 Provider 是否可用。
     * @param userId 当前 owner UUID
     * @return ready 状态和模型信息
     */
    Map<String, Object> requireReady(UUID userId);

    /**
     * 识别单张图片是否属于视觉端口支持的类型。
     * @param path owner 相对路径
     * @return 支持时为 true
     */
    boolean isImage(String path);

    /**
     * 生成单张图片描述。
     * @param userId 图片归属 owner UUID
     * @param path owner 相对路径
     * @return 图片路径、模型和综合描述
     */
    Map<String, Object> describeFile(UUID userId, String path);
}
