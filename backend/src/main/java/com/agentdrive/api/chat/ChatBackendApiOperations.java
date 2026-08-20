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
                http("POST", "/api/v1/config", "保存并测试当前 owner 的 LLM provider 配置"),
                http("POST", "/api/v1/config/test", "测试 LLM provider 连接但不保存配置"),
                http("POST", "/api/v1/config/models", "读取 provider 可用模型列表"),
                http("GET", "/api/v1/config/vision", "读取当前 owner 的视觉模型配置状态"),
                http("POST", "/api/v1/config/vision/models", "读取视觉 provider 可用模型列表"),
                http("PUT", "/api/v1/config/vision", "保存并测试 OpenAI 兼容视觉模型配置"),
                http("POST", "/api/v1/vision/describe", "按 body.files 返回图片结构化描述"),
                http("PUT", "/api/v1/config/embeddings", "保存并测试 embedding 配置"),
                http("GET", "/api/v1/automation/latest", "读取当前 owner 最近一次自动化报告"),
                http("GET", "/api/v1/sessions", "列出当前 owner 的会话"),
                http("GET", "/api/v1/sessions/{sessionId}", "读取当前 owner 的会话消息"),
                http("POST", "/api/v1/sessions/{sessionId}/summarize", "生成当前 owner 会话摘要"),
                http("DELETE", "/api/v1/sessions/{sessionId}", "删除当前 owner 的会话"),
                http("GET", "/api/v1/devices", "列出当前 owner 的设备"),
                http("POST", "/api/v1/devices/register", "登记设备并更新同步状态"),
                http("DELETE", "/api/v1/devices/{device_id}", "移除设备并撤销其令牌"),
                http("GET", "/api/v1/tasks", "列出当前 owner 的顶层任务"),
                http("GET", "/api/v1/tasks/summary", "读取当前 owner 的任务概览"),
                http("POST", "/api/v1/tasks/rebuild-index", "排队重建索引任务"),
                http("POST", "/api/v1/tasks/embed-index", "body.files 为相对路径列表，排队文本抽取和向量化任务"),
                http("POST", "/api/v1/tasks/vision-index", "body.files 为图片相对路径列表，排队视觉描述和向量化任务"),
                http("POST", "/api/v1/tasks/cleanup-index", "排队清理索引任务"),
                http("GET", "/api/v1/tasks/{task_id}", "读取任务和子任务"),
                http("POST", "/api/v1/tasks/{task_id}/cancel", "请求取消任务"),
                http("POST", "/api/v1/tasks/{task_id}/retry", "重试失败或取消的任务"),
                http("GET", "/api/v1/schedules", "列出当前 owner 的自动化计划"),
                http("PUT", "/api/v1/schedules/{name}", "保存当前 owner 的自动化计划"),
                http("DELETE", "/api/v1/schedules/{name}", "删除当前 owner 的自动化计划"),
                http("GET", "/api/v1/skills", "分页搜索当前 owner 的内置与自定义 Skill"),
                http("GET", "/api/v1/skills/{name}", "读取 Skill 完整指令"),
                http("PUT", "/api/v1/skills/{name}", "创建、更新或启停自定义 Skill"),
                http("DELETE", "/api/v1/skills/{name}", "删除自定义 Skill"),
                http("GET", "/api/v1/files", "列出目录内容；可用 q + mode=semantic 做语义搜索"),
                http("GET", "/api/v1/files/info", "读取文件信息和预览摘要"),
                http("GET", "/api/v1/files/content", "读取文本文件的受限完整内容"),
                http("GET", "/api/v1/files/dedupe", "按服务端 MD5 查询已上传文件"),
                http("GET", "/api/v1/files/trash", "列出回收站内容"),
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
