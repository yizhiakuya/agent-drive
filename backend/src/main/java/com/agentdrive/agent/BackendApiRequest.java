package com.agentdrive.agent;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * {@code backend_api} 工具的统一请求信封。
 *
 * <p>{@code action} 控制 discover 或 call；discover 可携带分页窗口，call 的其余字段
 * 分别承载已登记 operation 允许的路径参数、查询参数、JSON 请求体和文件元数据。
 * 紧凑构造器把空容器统一成不可变空 Map，并将缺省 action 设为 discover，避免后续
 * 分发代码处理 null。</p>
 */
public record BackendApiRequest(
        String action,
        String query,
        @JsonProperty("discovery_offset") Integer discoveryOffset,
        @JsonProperty("discovery_limit") Integer discoveryLimit,
        String operation,
        @JsonProperty("path_params") Map<String, String> pathParams,
        @JsonProperty("query_params") Map<String, Object> queryParams,
        Map<String, Object> body,
        Map<String, Object> files
) {
    /**
     * 规范化工具请求的可选字段。
     *
     * @param action {@code discover} 或 {@code call}；为空时默认为 discover
     * @param query discover 使用的自然语言检索词
     * @param discoveryOffset discover 结果起始偏移；为空时从 0 开始
     * @param discoveryLimit discover 单页数量；为空时使用目录默认值
     * @param operation call 要执行的精确 operation 名称
     * @param pathParams operation 声明的路径变量
     * @param queryParams operation 声明的查询参数
     * @param body operation 声明的 JSON 请求体
     * @param files operation 声明的文件元数据
     */
    public BackendApiRequest {
        action = action == null || action.isBlank() ? "discover" : action;
        pathParams = pathParams == null ? Map.of() : Map.copyOf(pathParams);
        queryParams = queryParams == null ? Map.of() : Map.copyOf(queryParams);
        body = body == null ? Map.of() : Map.copyOf(body);
        files = files == null ? Map.of() : Map.copyOf(files);
    }

    /**
     * 保留不带 discover 分页字段的请求构造方式。
     *
     * @param action {@code discover} 或 {@code call}
     * @param query discover 检索词
     * @param operation call 的精确 operation
     * @param pathParams 路径参数
     * @param queryParams 查询参数
     * @param body JSON 请求体
     * @param files 文件元数据
     */
    public BackendApiRequest(String action, String query, String operation,
                             Map<String, String> pathParams, Map<String, Object> queryParams,
                             Map<String, Object> body, Map<String, Object> files) {
        this(action, query, null, null, operation, pathParams, queryParams, body, files);
    }

    /**
     * 判断该请求是否为 operation 发现请求。
     *
     * @return action 精确为 {@code discover} 时为 {@code true}
     */
    public boolean isDiscover() {
        return "discover".equals(action);
    }

    /**
     * 判断该请求是否为 operation 调用请求。
     *
     * @return action 精确为 {@code call} 时为 {@code true}
     */
    public boolean isCall() {
        return "call".equals(action);
    }
}
