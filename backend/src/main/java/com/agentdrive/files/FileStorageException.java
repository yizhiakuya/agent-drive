package com.agentdrive.files;

/**
 * 文件存储操作失败时携带稳定 HTTP 状态码的运行时异常。
 * 存储实现用它区分路径非法、文件不存在、冲突等可映射的业务错误；上层控制器据此生成 API 响应，
 * 而无需解析底层 {@link java.io.IOException} 的文本。
 */
public final class FileStorageException extends RuntimeException {
    private final int status;

    /**
     * 创建一个没有底层原因的存储异常。
     *
     * @param status 应返回给 API 客户端的 HTTP 状态码。
     * @param message 面向日志和 API 错误处理的稳定错误说明，不应包含密钥或令牌。
     */
    public FileStorageException(int status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * 创建一个保留底层原因的存储异常。
     * 原因用于服务端诊断，外部响应仍应使用 {@code status} 和 {@code message}，避免泄露文件系统细节。
     *
     * @param status 应返回给 API 客户端的 HTTP 状态码。
     * @param message 面向日志和 API 错误处理的稳定错误说明。
     * @param cause 导致本次存储失败的底层异常。
     */
    public FileStorageException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /**
     * 返回该异常对应的 HTTP 状态码。
     *
     * @return 存储层为该错误指定的 HTTP 状态码。
     */
    public int status() {
        return status;
    }
}
