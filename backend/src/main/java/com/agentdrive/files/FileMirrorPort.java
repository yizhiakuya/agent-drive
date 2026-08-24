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

    /** 删除远程文件或目录镜像树。 */
    default void deletePath(UUID ownerId, String path) {
        deleteFile(ownerId, path);
    }

    /** 镜像移动文件或目录。 */
    default void movePath(UUID ownerId, String source, String destination, boolean overwrite) {
        throw new UnsupportedOperationException("file mirror move is not supported");
    }

    /** 镜像复制文件或目录。 */
    default void copyPath(UUID ownerId, String source, String destination, boolean overwrite) {
        throw new UnsupportedOperationException("file mirror copy is not supported");
    }

    /** 把路径移入远程回收站。 */
    default void trashPath(UUID ownerId, String path, String trashId) {
        throw new UnsupportedOperationException("file mirror trash is not supported");
    }

    /** 从远程回收站恢复路径。 */
    default void restorePath(UUID ownerId, String trashId, String path) {
        throw new UnsupportedOperationException("file mirror restore is not supported");
    }

    /** 提交后永久删除远程回收站条目。 */
    default void emptyTrash(UUID ownerId, String trashId) {
        throw new UnsupportedOperationException("file mirror trash cleanup is not supported");
    }

    /** 返回不产生网络请求的空镜像实现。 */
    static FileMirrorPort noop() {
        return new FileMirrorPort() {
            @Override
            public void syncFile(UUID ownerId, String path, long revision, byte[] bytes, String contentMd5) {
            }

            @Override
            public void deleteFile(UUID ownerId, String path) {
            }

            @Override
            public void deletePath(UUID ownerId, String path) {
            }

            @Override
            public void movePath(UUID ownerId, String source, String destination, boolean overwrite) {
            }

            @Override
            public void copyPath(UUID ownerId, String source, String destination, boolean overwrite) {
            }

            @Override
            public void trashPath(UUID ownerId, String path, String trashId) {
            }

            @Override
            public void restorePath(UUID ownerId, String trashId, String path) {
            }

            @Override
            public void emptyTrash(UUID ownerId, String trashId) {
            }
        };
    }
}
