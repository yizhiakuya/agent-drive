package com.agentdrive.fileservice;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/** File Service 的内部认证、存储根和读取上限配置。 */
@ConfigurationProperties(prefix = "file")
public record FileServiceProperties(String internalToken, String storageRoot, Long maxReadBytes) {
    /** 在配置边界固定安全默认值。 */
    public FileServiceProperties {
        internalToken = clean(internalToken);
        storageRoot = clean(storageRoot).isBlank() ? "data" : clean(storageRoot);
        maxReadBytes = maxReadBytes == null ? 50L * 1024 * 1024
                : Math.max(1, Math.min(maxReadBytes, 300L * 1024 * 1024));
    }

    /** 返回规范化后的存储根路径。 */
    public Path rootPath() {
        return Path.of(storageRoot).toAbsolutePath().normalize();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
