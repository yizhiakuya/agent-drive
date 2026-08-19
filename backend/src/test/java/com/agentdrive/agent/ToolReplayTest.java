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
}
