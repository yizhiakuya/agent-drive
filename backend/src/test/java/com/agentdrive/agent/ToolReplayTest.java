package com.agentdrive.agent;

import com.agentdrive.api.chat.ChatRequest;
import com.agentdrive.api.chat.ChatSseEvent;
import com.agentdrive.api.chat.LangChainAgentRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolReplayTest {
    @Test
    void replaysYellowResultWithoutCallingDispatcherAgain() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger executions = new AtomicInteger();
        BackendApiTool backendApiTool = new BackendApiTool(
                new OperationCatalog(List.of(OperationDefinition.http(
                        "POST", "/api/v1/config/test", "Test provider"
                ))),
                (operation, request) -> {
                    executions.incrementAndGet();
                    return Map.of("ok", true, "tested", true);
                },
                mapper
        );
        LangChainAgentRuntime runtime = new LangChainAgentRuntime(
                new AlternatingToolModel(), backendApiTool, mapper, new OpenAiChatRequestFactory()
        );

        List<ChatSseEvent> first = runtime.stream(new ChatRequest(
                "测试", null, null, "session-1", "auto"
        )).collectList().block(Duration.ofSeconds(2));
        List<ChatSseEvent> second = runtime.stream(new ChatRequest(
                "再试一次", null, null, "session-1", "auto"
        )).collectList().block(Duration.ofSeconds(2));

        assertThat(first).extracting(ChatSseEvent::event)
                .containsExactly("tool_start", "tool_trace", "text", "done");
        assertThat(second).extracting(ChatSseEvent::event)
                .containsExactly("tool_start", "tool_trace", "text", "done");
        assertThat(first.get(1).data()).doesNotContainKey("replayed");
        assertThat(second.get(1).data()).containsEntry("replayed", true);
        assertThat(executions).hasValue(1);
    }

    @Test
    void doesNotReplayMutableGetSnapshots() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger executions = new AtomicInteger();
        BackendApiTool backendApiTool = new BackendApiTool(
                new OperationCatalog(List.of(OperationDefinition.http(
                        "GET", "/api/v1/files", "List files"))),
                (operation, request) -> Map.of("ok", true, "generation", executions.incrementAndGet()),
                mapper
        );
        LangChainAgentRuntime runtime = new LangChainAgentRuntime(
                new AlternatingGetModel(), backendApiTool, mapper, new OpenAiChatRequestFactory()
        );

        runtime.stream(new ChatRequest("第一次", null, null, "get-session", "auto"))
                .collectList().block(Duration.ofSeconds(2));
        runtime.stream(new ChatRequest("第二次", null, null, "get-session", "auto"))
                .collectList().block(Duration.ofSeconds(2));

        assertThat(executions).hasValue(2);
    }

    @Test
    void doesNotCacheTransientToolFailures() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger executions = new AtomicInteger();
        BackendApiTool backendApiTool = new BackendApiTool(
                new OperationCatalog(List.of(OperationDefinition.http(
                        "POST", "/api/v1/config/test", "Probe provider"))),
                (operation, request) -> {
                    if (executions.incrementAndGet() == 1) throw new IllegalStateException("temporary");
                    return Map.of("ok", true, "ready", true);
                },
                mapper
        );
        LangChainAgentRuntime runtime = new LangChainAgentRuntime(
                new AlternatingProbeModel(), backendApiTool, mapper, new OpenAiChatRequestFactory()
        );

        runtime.stream(new ChatRequest("第一次", null, null, "failure-session", "auto"))
                .collectList().block(Duration.ofSeconds(2));
        runtime.stream(new ChatRequest("第二次", null, null, "failure-session", "auto"))
                .collectList().block(Duration.ofSeconds(2));

        assertThat(executions).hasValue(2);
    }

    @Test
    void replayStoreRedactsSensitiveSnapshots() {
        ObjectMapper mapper = new ObjectMapper();
        InMemoryToolReplayStore store = new InMemoryToolReplayStore(mapper);
        store.save("safe-session", "probe", Map.of("api_key", "sk-secret-value"),
                "Bearer provider-secret", Map.of("api_key", "sk-secret-value"));

        ToolReplayStore.ToolReplay replay = store.find("safe-session", "probe",
                Map.of("api_key", "sk-secret-value"));
        assertThat(replay).isNotNull();
        assertThat(replay.output()).doesNotContain("provider-secret");
        assertThat(replay.parsed().toString()).doesNotContain("sk-secret-value");
    }

    private static final class AlternatingToolModel implements StreamingChatModel {
        private int calls;

        @Override
        public void doChat(dev.langchain4j.model.chat.request.ChatRequest request,
                           StreamingChatResponseHandler handler) {
            if (calls++ % 2 == 0) {
                ToolExecutionRequest tool = ToolExecutionRequest.builder()
                        .id("test-call")
                        .name("backend_api")
                        .arguments("{\"action\":\"call\",\"operation\":\"POST /api/v1/config/test\"}")
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

    private static final class AlternatingGetModel implements StreamingChatModel {
        private int calls;

        @Override
        public void doChat(dev.langchain4j.model.chat.request.ChatRequest request,
                           StreamingChatResponseHandler handler) {
            if (calls++ % 2 == 0) {
                handler.onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                                .id("get-call")
                                .name("backend_api")
                                .arguments("{\"action\":\"call\",\"operation\":\"GET /api/v1/files\"}")
                                .build())))
                        .build());
            } else {
                handler.onPartialResponse("完成");
                handler.onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(AiMessage.from("完成")).build());
            }
        }
    }

    private static final class AlternatingProbeModel implements StreamingChatModel {
        private int calls;

        @Override
        public void doChat(dev.langchain4j.model.chat.request.ChatRequest request,
                           StreamingChatResponseHandler handler) {
            if (calls++ % 2 == 0) {
                handler.onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                                .id("probe-call")
                                .name("backend_api")
                                .arguments("{\"action\":\"call\",\"operation\":\"POST /api/v1/config/test\"}")
                                .build())))
                        .build());
            } else {
                handler.onPartialResponse("完成");
                handler.onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(AiMessage.from("完成")).build());
            }
        }
    }
}
