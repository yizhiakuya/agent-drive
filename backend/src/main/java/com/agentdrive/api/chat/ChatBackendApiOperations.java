package com.agentdrive.api.chat;

import com.agentdrive.agent.BackendApiDispatcher;
import com.agentdrive.agent.BackendApiRequest;
import com.agentdrive.agent.OperationCatalog;
import com.agentdrive.agent.OperationDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Registers the Agent operation catalog and routes calls to owner-scoped domain handlers. */
@Configuration
@Profile("java-chat")
public class ChatBackendApiOperations {
    @Bean
    OperationCatalog operationCatalog() {
        return new OperationCatalog(List.of(
                OperationDefinition.internal("write_text", "在当前 owner 文件区原子写入 UTF-8 文本", "red"),
                http("GET", "/api/v1/config", "读取当前 owner 的 LLM provider 配置（不返回 API key）"),
                http("GET", "/api/v1/config/status", "读取当前 owner 的 LLM 与 embedding 配置状态"),
                http("GET", "/api/v1/index", "读取当前 owner 的索引状态和索引文件列表"),
                http("GET", "/api/v1/index/file", "读取单个文件的索引元数据"),
                http("PUT", "/api/v1/index/file", "直接抽取并写入单个文本文件索引"),
                http("PUT", "/api/v1/index/vision", "直接识别、写入视觉描述并向量化图片或目录"),
                http("PUT", "/api/v1/index/vectors", "直接向量化当前索引文档块"),
                http("DELETE", "/api/v1/index/vectors", "直接删除当前 owner 的文本和视觉向量，保留正文和原文件"),
                http("DELETE", "/api/v1/index/stale", "删除当前 owner 已失效的索引文档"),
                http("POST", "/api/v1/index/rebuild", "直接重建指定范围的全文索引"),
                http("GET", "/api/v1/config/vision", "读取当前 owner 的视觉模型配置状态"),
                http("POST", "/api/v1/vision/describe", "按 body.files 返回图片综合文字描述"),
                http("GET", "/api/v1/automation/latest", "读取当前 owner 最近一次自动化报告"),
                http("GET", "/api/v1/sessions", "列出当前 owner 的会话"),
                http("GET", "/api/v1/sessions/{sessionId}", "读取当前 owner 的会话消息"),
                http("POST", "/api/v1/sessions/{sessionId}/summarize", "生成当前 owner 会话摘要"),
                http("DELETE", "/api/v1/sessions/{sessionId}", "删除当前 owner 的会话"),
                http("GET", "/api/v1/devices", "列出当前 owner 的设备"),
                http("POST", "/api/v1/devices/register", "登记设备并更新同步状态"),
                http("DELETE", "/api/v1/devices/{device_id}", "移除设备并撤销其令牌"),
                http("GET", "/api/v1/skills", "分页搜索当前 owner 的内置与自定义 Skill"),
                http("GET", "/api/v1/skills/{name}", "读取 Skill 完整指令"),
                http("PUT", "/api/v1/skills/{name}", "创建、更新或启停自定义 Skill"),
                http("DELETE", "/api/v1/skills/{name}", "删除自定义 Skill"),
                http("GET", "/api/v1/files", "列出目录内容；支持名称/语义、类型和修改时间筛选"),
                http("GET", "/api/v1/files/search-content", "检索可供回答的多段文件证据和相邻上下文"),
                http("GET", "/api/v1/files/stats", "服务端递归统计当前 owner 的文件、目录和字节数"),
                http("GET", "/api/v1/files/info", "读取文件信息和预览摘要"),
                http("GET", "/api/v1/files/content", "读取文本文件的受限完整内容"),
                http("GET", "/api/v1/files/dedupe", "按服务端 MD5 查询已上传文件"),
                http("GET", "/api/v1/files/trash", "列出回收站内容"),
                http("GET", "/api/v1/files/favorites", "列出当前 owner 收藏的文件和目录"),
                http("POST", "/api/v1/files/favorites", "收藏当前 owner 的文件或目录"),
                http("DELETE", "/api/v1/files/favorites", "取消当前 owner 的文件或目录收藏"),
                http("GET", "/api/v1/files/recent", "列出当前 owner 最近访问的文件"),
                http("GET", "/api/v1/files/versions", "列出当前 owner 文件的真实内容版本"),
                http("POST", "/api/v1/files/versions/restore", "将指定文件版本恢复为新 revision"),
                http("POST", "/api/v1/files/mkdir", "创建目录"),
                http("POST", "/api/v1/files/rename", "重命名文件或目录"),
                http("POST", "/api/v1/files/move", "移动文件或目录"),
                http("POST", "/api/v1/files/copy", "复制文件或目录"),
                http("POST", "/api/v1/files/delete", "将文件或目录移入回收站"),
                http("POST", "/api/v1/files/trash/restore", "恢复回收站文件"),
                http("POST", "/api/v1/files/trash/empty", "清空回收站")
        ));
    }

    @Bean
    BackendApiDispatcher backendApiDispatcher(List<BackendApiOperationHandler> handlers) {
        Map<String, BackendApiOperationHandler> routes = routes(handlers);
        return new BackendApiDispatcher() {
            @Override
            public Map<String, Object> dispatch(OperationDefinition operation, BackendApiRequest request) {
                return BackendApiResponses.notImplemented(operation);
            }

            @Override
            public Map<String, Object> dispatch(OperationDefinition operation,
                                                BackendApiRequest request,
                                                UUID userId) {
                if (userId == null) return BackendApiResponses.missingOwner();
                BackendApiOperationHandler handler = routes.get(operation.operation());
                return handler == null
                        ? BackendApiResponses.notImplemented(operation)
                        : handler.dispatch(operation.operation(), request, userId);
            }
        };
    }

    private Map<String, BackendApiOperationHandler> routes(List<BackendApiOperationHandler> handlers) {
        Map<String, BackendApiOperationHandler> routes = new LinkedHashMap<>();
        for (BackendApiOperationHandler handler : handlers) {
            for (String operation : handler.operations()) {
                BackendApiOperationHandler previous = routes.putIfAbsent(operation, handler);
                if (previous != null) {
                    throw new IllegalStateException("Duplicate backend_api handler for " + operation);
                }
            }
        }
        return Map.copyOf(routes);
    }

    private static OperationDefinition http(String method, String path, String summary) {
        return OperationDefinition.http(method, path, summary);
    }
}
