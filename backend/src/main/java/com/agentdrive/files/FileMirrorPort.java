package com.agentdrive.files;

import java.util.UUID;

/**
 * 文件 mutation 的远程镜像端口。
 *
 * <p>该端口只在显式启用 File Service 时使用；默认实现为空操作，保持模块化单体行为。
 * 写入失败必须抛出异常，避免调用方把未同步的 mutation 当作成功。</p>
 */
public interface FileMirrorPort {
    /** 镜像一个当前 revision 的普通文件。 */
    void syncFile(UUID ownerId, String path, long revision, byte[] bytes, String contentMd5);

    /** 删除一个远程文件镜像。 */
    void deleteFile(UUID ownerId, String path);

    /** 返回不产生网络请求的空镜像实现。 */
    static FileMirrorPort noop() {
        return new FileMirrorPort() {
            @Override
            public void syncFile(UUID ownerId, String path, long revision, byte[] bytes, String contentMd5) {
            }

            @Override
            public void deleteFile(UUID ownerId, String path) {
            }
        };
    }
}
