package com.agentdrive.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证动态前端能力清单、discover/call 信封和浏览器动作事件契约。
 */
class FrontendActionToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 前端能力 schema 只作为 discover 数据，不会变成 LangChain 工具字段。
     */
    @Test
    void exposesOneGenericToolAndDiscoversOnlyMatchingCapabilities() throws Exception {
        FrontendActionTool tool = new FrontendActionTool(mapper);
        List<ToolSpecification> specifications = ToolSpecifications.toolSpecificationsFrom(FrontendActionTool.class);

        assertThat(specifications).hasSize(1);
        assertThat(specifications.get(0).name()).isEqualTo("frontend_api");
        assertThat(specifications.get(0).parameters().properties())
                .containsKeys("action", "operation", "arguments")
                .doesNotContainKey("files.open");

        JsonNode result = mapper.readTree(tool.execute(
                new FrontendActionTool.FrontendActionRequest("discover", "暂停", null, null),
                List.of(capability("player.pause", "暂停当前播放器", "chat", Map.of()))));

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("frontend_actions").findValuesAsText("operation"))
                .containsExactly("player.pause");
    }

    /**
     * call 只能调用当前浏览器清单中的动作，并拒绝文件路径穿越。
     */
    @Test
    void validatesRegisteredOperationAndPathBeforeEmittingAction() throws Exception {
        FrontendActionTool tool = new FrontendActionTool(mapper);
        List<Map<String, Object>> capabilities = List.of(capability(
                "files.open", "打开文件", "files",
                Map.of(
                        "type", "object",
                        "required", List.of("path"),
                        "properties", Map.of("path", Map.of("type", "string"))
                )));

        JsonNode unknown = mapper.readTree(tool.execute(
                new FrontendActionTool.FrontendActionRequest("call", null, "files.delete", Map.of("path", "a")),
                capabilities));
        assertThat(unknown.path("ok").asBoolean()).isFalse();
        assertThat(unknown.path("error").asText()).isEqualTo("unknown_operation");

        JsonNode traversal = mapper.readTree(tool.execute(
                new FrontendActionTool.FrontendActionRequest("call", null, "files.open", Map.of("path", "../secret")),
                capabilities));
        assertThat(traversal.path("ok").asBoolean()).isFalse();
        assertThat(traversal.path("error").asText()).isEqualTo("invalid_path");

        JsonNode valid = mapper.readTree(tool.execute(
                new FrontendActionTool.FrontendActionRequest("call", null, "files.open", Map.of("path", "docs/readme.md")),
                capabilities));
        assertThat(valid.path("ok").asBoolean()).isTrue();
        assertThat(valid.path("frontend_action").path("operation").asText()).isEqualTo("files.open");
        assertThat(valid.path("frontend_action").path("arguments").path("path").asText())
                .isEqualTo("docs/readme.md");
        assertThat(tool.clientEvent(mapper.convertValue(valid, Map.class))).isNotNull();
    }

    /**
     * 构造前端动态能力清单项。
     *
     * @param operation 动作名
     * @param summary 动作用途
     * @param targetTab 目标页签
     * @param parameters 参数 schema
     * @return JSON 兼容的能力对象
     */
    private Map<String, Object> capability(String operation, String summary, String targetTab,
                                           Map<String, Object> parameters) {
        return Map.of(
                "operation", operation,
                "summary", summary,
                "target_tab", targetTab,
                "parameters", parameters
        );
    }
}
