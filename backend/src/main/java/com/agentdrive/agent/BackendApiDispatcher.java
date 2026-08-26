package com.agentdrive.agent;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 执行已通过 {@link OperationCatalog} 注册的后端操作。
 *
 * <p>实现负责把受限请求交给具体 API 或内部服务；调用方提供的用户 ID 用于
 * owner-scoped 数据隔离。接口不允许调用方绕过 operation 定义自行指定 URL 或
 * 请求头。</p>
 */
@FunctionalInterface
public interface BackendApiDispatcher {
    /**
     * 执行一个已登记的 operation。
     *
     * @param operation 已通过目录校验的 operation 定义
     * @param request 按 operation schema 组织的路径、查询和请求体数据
     * @return 脱敏后的结构化操作结果
     */
    Map<String, Object> dispatch(OperationDefinition operation, BackendApiRequest request);

    /**
     * 执行 operation 的 owner-aware 变体。
     *
     * <p>默认实现兼容不需要用户上下文的旧 dispatcher；需要访问用户文件、会话或
     *配置的实现应覆盖此方法并使用 {@code userId} 做权限边界。</p>
     *
     * @param operation 已登记的操作定义
     * @param request 已校验的操作参数
     * @param userId 当前认证用户；无认证上下文时可能为 {@code null}
     * @return 操作结果
     */
    default Map<String, Object> dispatch(OperationDefinition operation,
                                         BackendApiRequest request,
                                         UUID userId) {
        return dispatch(operation, request);
    }

    /**
     * Owner-aware operation dispatch with an optional live progress callback.
     * Existing dispatchers remain source-compatible and simply ignore progress.
     */
    default Map<String, Object> dispatch(OperationDefinition operation,
                                         BackendApiRequest request,
                                         UUID userId,
                                         Consumer<Map<String, Object>> progressListener) {
        return dispatch(operation, request, userId);
    }
}
