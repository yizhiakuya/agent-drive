package com.agentdrive.agent;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * {@code backend_api} 工具的统一请求信封。
 *
 * <p>{@code action} 控制 discover 或 call；其余字段分别承载已登记 operation
 * 允许的路径参数、查询参数、JSON 请求体和文件元数据。紧凑构造器把空容器统一
 * 成不可变空 Map，并将缺省 action 设为 discover，避免后续分发代码处理 null。</p>
 */
public record BackendApiRequest(
        String action,
        String query,
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
