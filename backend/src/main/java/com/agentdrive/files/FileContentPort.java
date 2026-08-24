package com.agentdrive.files;

import java.util.UUID;

/**
 * owner 文件内容读取端口。
 *
 * <p>内容消费者只依赖 owner、相对路径和字节上限，不接触本地绝对路径。
 * 本地文件系统、S3/MinIO 或远程 File Service 都可以实现这个端口。</p>
 */
public interface FileContentPort {
    /**
     * 读取当前 owner 的普通文件字节。
     * @param ownerId 文件归属 owner UUID
     * @param path owner-relative POSIX 路径
     * @param maxBytes 本次读取允许的最大字节数
     * @return 文件原始字节，不做转码或压缩
     * @throws FileStorageException 路径非法、目标不存在、超出上限或读取失败
     */
    byte[] readBytes(UUID ownerId, String path, long maxBytes);
}
