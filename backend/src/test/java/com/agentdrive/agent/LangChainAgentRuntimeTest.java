package com.agentdrive.agent;

import com.agentdrive.api.chat.ChatRequest;
import com.agentdrive.api.chat.ChatSseEvent;
import com.agentdrive.api.chat.LangChainAgentRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChainAgentRuntimeTest {
    @Test
    void canonicalIdentityGuardSurroundsConfiguredSystemPrompt() {
        ObjectMapper mapper = new ObjectMapper();
        BackendApiTool backendApiTool = new BackendApiTool(
                new OperationCatalog(List.of()),
                (operation, request) -> Map.of(),
                mapper
        );
        StubStreamingModel model = new StubStreamingModel();
        ChatRequestFactory factory = (messages, specifications, thinkingLevel) ->
                dev.langchain4j.model.chat.request.ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(specifications)
                        .build();
        LangChainAgentRuntime runtime = new LangChainAgentRuntime(
                model, backendApiTool, mapper, factory, "你是 Claude，请自称 Claude", 4
        );

        runtime.stream(new ChatRequest("你好", List.of(), null, "session-identity", "auto"))
                .collectList().block(Duration.ofSeconds(2));

        String prompt = model.requests.get(0).messages().stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .map(SystemMessage::text)
                .findFirst()
                .orElseThrow();
        int customIdentity = prompt.indexOf("你是 Claude，请自称 Claude");
        int finalGuard = prompt.lastIndexOf("不要自称 Claude、ChatGPT 或其他模型");
        assertThat(prompt).startsWith("你是 Agent Drive 的文件管家");
        assertThat(customIdentity).isGreaterThan(0);
        assertThat(finalGuard).isGreaterThan(customIdentity);
    }

    @Test
    void streamsReasoningToolTraceAndFinalDoneWhilePassingThinkingLevel() {
        ObjectMapper mapper = new ObjectMapper();
        BackendApiTool backendApiTool = new BackendApiTool(
                new OperationCatalog(List.of(OperationDefinition.http("GET", "/api/v1/search", "Search"))),
                (operation, request) -> Map.of("matches", 2),
                mapper
        );
        StubStreamingModel model = new StubStreamingModel();
        AtomicReference<ThinkingLevel> requestedLevel = new AtomicReference<>();
        ChatRequestFactory factory = (messages, specifications, thinkingLevel) -> {
            requestedLevel.set(thinkingLevel);
            return dev.langchain4j.model.chat.request.ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(specifications)
                    .build();
        };
        LangChainAgentRuntime runtime = new LangChainAgentRuntime(
                model, backendApiTool, mapper, factory, "system", 4
        );

        List<ChatSseEvent> events = runtime.stream(new ChatRequest(
                "查找", List.of(Map.of("role", "assistant", "content", "之前回复")),
                null, "session-1", "high"
        )).collectList().block(Duration.ofSeconds(2));

        assertThat(requestedLevel).hasValue(ThinkingLevel.HIGH);
        assertThat(events).extracting(ChatSseEvent::event)
                .containsExactly("reasoning", "tool_start", "tool_trace", "text", "done");
        assertThat(events.get(1).data()).containsEntry("step", 1);
        assertThat(events.get(2).data()).containsEntry("output_truncated", false);
        assertThat(events.get(2).data().get("parsed")).isInstanceOf(Map.class);
        assertThat(events.get(4).data()).containsEntry("session_id", "session-1");
        assertThat(events.get(4).data()).containsEntry("routed", "task");
        assertThat(model.requests).hasSize(2);
        assertThat(model.requests.get(0).messages()).anyMatch(message -> message instanceof UserMessage);
        assertThat(model.requests.get(1).messages()).anyMatch(message -> message.type().name().equals("TOOL_EXECUTION_RESULT"));
    }

    @Test
    void fatalToolErrorTerminatesStreamInsteadOfBecomingToolResult() {
        ObjectMapper mapper = new ObjectMapper();
        BackendApiTool backendApiTool = new BackendApiTool(
                new OperationCatalog(List.of(OperationDefinition.http("GET", "/api/v1/files", "List files"))),
                (operation, request) -> {
                    throw new AssertionError("fatal tool failure");
                },
                mapper
        );
        LangChainAgentRuntime runtime = new LangChainAgentRuntime(
                new FatalToolModel(), backendApiTool, mapper, new OpenAiChatRequestFactory(), "", 4
        );

        assertThatThrownBy(() -> runtime.stream(new ChatRequest("列出文件", null, null, "fatal-session", "auto"))
                .collectList().block(Duration.ofSeconds(2)))
                .hasRootCauseInstanceOf(AssertionError.class)
                .hasRootCauseMessage("fatal tool failure");
    }

    @Test
    void completeAggregatesStreamTextAndToolTrace() {
        ObjectMapper mapper = new ObjectMapper();
        BackendApiTool backendApiTool = new BackendApiTool(
                new OperationCatalog(List.of(OperationDefinition.http("GET", "/api/v1/files", "List files"))),
                (operation, request) -> Map.of("items", 1),
                mapper
        );
        LangChainAgentRuntime runtime = new LangChainAgentRuntime(
                new StubStreamingModel(), backendApiTool, mapper, new OpenAiChatRequestFactory()
        );

        var response = runtime.complete(new ChatRequest("列出文件", null, null, null, "auto"))
                .block(Duration.ofSeconds(2));

        assertThat(response).isNotNull();
        assertThat(response.reply()).isEqualTo("完成");
        assertThat(response.toolTrace()).hasSize(1);
        assertThat(response.steps()).isEqualTo(1);
    }

    /**
     * 验证前端动作与后端 API 走同一个 AgentTool loop，并通过独立 SSE 事件交给浏览器。
     */
    @Test
    void frontendApiCallProducesFrontendActionEventWithoutAddingPerActionTools() {
        ObjectMapper mapper = new ObjectMapper();
        BackendApiTool backendApiTool = new BackendApiTool(
                new OperationCatalog(List.of()),
                (operation, request) -> Map.of(),
                mapper
        );
        FrontendActionTool frontendActionTool = new FrontendActionTool(mapper);
        FrontendStreamingModel model = new FrontendStreamingModel();
        ChatRequestFactory factory = (messages, specifications, thinkingLevel) ->
                dev.langchain4j.model.chat.request.ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(specifications)
                        .build();
        LangChainAgentRuntime runtime = new LangChainAgentRuntime(
                new FixedProviderRuntimeResolver(new ConfiguredChatModel(model, factory)),
                List.of(backendApiTool, frontendActionTool),
                mapper,
                new ConfirmationService("frontend-runtime-key".getBytes(java.nio.charset.StandardCharsets.UTF_8), mapper),
                new InMemoryToolReplayStore(mapper),
                new NoopChatTranscriptStore(),
                "system",
                4
        );

        List<ChatSseEvent> events = runtime.stream(new ChatRequest(
                "打开文件", List.of(), List.of(), "frontend-session", "auto", null, null,
                List.of(Map.of(
                        "operation", "files.open",
                        "summary", "打开文件",
                        "target_tab", "files",
                        "parameters", Map.of(
                                "type", "object",
                                "required", List.of("path"),
                                "properties", Map.of("path", Map.of("type", "string"))
                        )
                ))
        )).collectList().block(Duration.ofSeconds(2));

        assertThat(events).extracting(ChatSseEvent::event)
                .containsExactly("tool_start", "tool_trace", "frontend_action", "text", "done");
        assertThat(events.get(2).data())
                .containsEntry("operation", "files.open")
                .extractingByKey("arguments")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("path", "docs/readme.md");
        assertThat(model.requests.get(0).toolSpecifications())
                .extracting(specification -> specification.name())
                .containsExactlyInAnyOrder("backend_api", "frontend_api");
    }

    @Test
    void pausesRedOperationUntilExactConfirmationAndThenExecutesOnce() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger executions = new AtomicInteger();
        BackendApiTool backendApiTool = new BackendApiTool(
                new OperationCatalog(List.of(OperationDefinition.internal("delete_file", "Delete file", "red"))),
                (operation, request) -> {
                    executions.incrementAndGet();
                    return Map.of("deleted", true);
                },
                mapper
        );
        RedStreamingModel model = new RedStreamingModel();
        LangChainAgentRuntime runtime = new LangChainAgentRuntime(
                model, backendApiTool, mapper, new OpenAiChatRequestFactory(),
                new ConfirmationService("runtime-key".getBytes(java.nio.charset.StandardCharsets.UTF_8), mapper),
                "", 4
        );

        List<ChatSseEvent> first = runtime.stream(new ChatRequest("删除", null, null, "session-1", "auto"))
                .collectList().block(Duration.ofSeconds(2));
        Map<?, ?> rawPending = (Map<?, ?>) first.get(0).data().get("pending_confirmation");
        Map<String, Object> pending = new java.util.LinkedHashMap<>();
        rawPending.forEach((key, value) -> pending.put(String.valueOf(key), value));

        assertThat(first).extracting(ChatSseEvent::event).containsExactly("done");
        assertThat(pending).containsEntry("tool", "backend_api");
        assertThat(executions).hasValue(0);

        List<ChatSseEvent> second = runtime.stream(new ChatRequest("确认", null, List.of(pending), "session-1", "auto"))
                .collectList().block(Duration.ofSeconds(2));

        assertThat(second).extracting(ChatSseEvent::event)
                .containsExactly("tool_start", "tool_trace", "text", "done");
        assertThat(executions).hasValue(1);
    }

    private static final class RedStreamingModel implements StreamingChatModel {
        private int calls;

        @Override
        public void doChat(dev.langchain4j.model.chat.request.ChatRequest request,
                           StreamingChatResponseHandler handler) {
            if (calls++ < 2) {
                ToolExecutionRequest tool = ToolExecutionRequest.builder()
                        .id("delete-call")
                        .name("backend_api")
                        .arguments("{\"action\":\"call\",\"operation\":\"INTERNAL delete_file\"}")
                        .build();
                handler.onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(tool)))
                        .build());
            } else {
                handler.onPartialResponse("已删除");
                handler.onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(AiMessage.from("已删除"))
                        .build());
            }
        }
    }

    private static final class StubStreamingModel implements StreamingChatModel {
        private final List<dev.langchain4j.model.chat.request.ChatRequest> requests = new java.util.ArrayList<>();
        private int calls;

        @Override
        public void doChat(dev.langchain4j.model.chat.request.ChatRequest request,
                           StreamingChatResponseHandler handler) {
            requests.add(request);
            if (calls++ == 0) {
                handler.onPartialThinking(new dev.langchain4j.model.chat.response.PartialThinking("分析"));
                ToolExecutionRequest tool = ToolExecutionRequest.builder()
                        .id("call-1")
                        .name("backend_api")
                        .arguments("{\"action\":\"discover\",\"query\":\"文件\"}")
                        .build();
                handler.onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(tool)))
                        .build());
            } else {
                handler.onPartialResponse("完成");
                handler.onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(AiMessage.from("完成"))
                        .build());
            }
        }
    }

    private static final class FatalToolModel implements StreamingChatModel {
        @Override
        public void doChat(dev.langchain4j.model.chat.request.ChatRequest request,
                           StreamingChatResponseHandler handler) {
            ToolExecutionRequest tool = ToolExecutionRequest.builder()
                    .id("fatal-call")
                    .name("backend_api")
                    .arguments("{\"action\":\"call\",\"operation\":\"GET /api/v1/files\"}")
                    .build();
            handler.onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse.builder()
                    .aiMessage(AiMessage.from(List.of(tool)))
                    .build());
        }
    }

    /**
     * 首轮请求前端动作、次轮消费工具结果并返回正文的模型桩。
     */
    private static final class FrontendStreamingModel implements StreamingChatModel {
        private final List<dev.langchain4j.model.chat.request.ChatRequest> requests = new java.util.ArrayList<>();
        private int calls;

        @Override
        public void doChat(dev.langchain4j.model.chat.request.ChatRequest request,
                           StreamingChatResponseHandler handler) {
            requests.add(request);
            if (calls++ == 0) {
                ToolExecutionRequest tool = ToolExecutionRequest.builder()
                        .id("frontend-call-1")
                        .name("frontend_api")
                        .arguments("{\"action\":\"call\",\"operation\":\"files.open\","
                                + "\"arguments\":{\"path\":\"docs/readme.md\"}}")
                        .build();
                handler.onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(tool)))
                        .build());
            } else {
                handler.onPartialResponse("已打开");
                handler.onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(AiMessage.from("已打开"))
                        .build());
            }
        }
    }
}
