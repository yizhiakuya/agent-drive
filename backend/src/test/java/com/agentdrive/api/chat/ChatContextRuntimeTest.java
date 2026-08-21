package com.agentdrive.api.chat;

import com.agentdrive.agent.ChatRequestFactory;
import com.agentdrive.agent.ChatTranscriptStore;
import com.agentdrive.agent.ConfiguredChatModel;
import com.agentdrive.agent.ConfirmationService;
import com.agentdrive.agent.FixedProviderRuntimeResolver;
import com.agentdrive.agent.InMemoryToolReplayStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatContextRuntimeTest {
    @Test
    void injectsUserContextAndEmitsOnlyNewDurableSnapshots() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<dev.langchain4j.model.chat.request.ChatRequest> modelRequest = new AtomicReference<>();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void doChat(dev.langchain4j.model.chat.request.ChatRequest request,
                               StreamingChatResponseHandler handler) {
                modelRequest.set(request);
                handler.onPartialResponse("done");
                handler.onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(AiMessage.from("done"))
                        .build());
            }
        };
        ChatRequestFactory factory = (messages, specifications, thinkingLevel) ->
                dev.langchain4j.model.chat.request.ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(specifications)
                        .build();
        ChatTranscriptStore transcript = mock(ChatTranscriptStore.class);
        when(transcript.appendContextIfChanged(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true, false);
        String normalizedPrompt = AgentSystemPrompt.normalize("system");
        ChatContextProvider contexts = ignored -> List.of(
                new ChatContext("agent-drive-system-prompt", "system", normalizedPrompt, false),
                new ChatContext("skill-catalog", "skill-catalog", "catalog instructions", true)
        );
        LangChainAgentRuntime runtime = new LangChainAgentRuntime(
                new FixedProviderRuntimeResolver(new ConfiguredChatModel(model, factory)),
                List.of(), mapper,
                ConfirmationService.random(mapper),
                new InMemoryToolReplayStore(mapper), transcript, contexts,
                "system", 4);

        List<ChatSseEvent> events = runtime.stream(new ChatRequest(
                        "hello", List.of(), List.of(), "session-context", "auto",
                        UUID.randomUUID(), "request-context"))
                .collectList().block(Duration.ofSeconds(2));

        assertThat(events).isNotNull();
        assertThat(events).extracting(ChatSseEvent::event).containsExactly("context", "text", "done");
        assertThat(events.get(0).data()).containsEntry("source", "agent-drive-system-prompt");
        assertThat(modelRequest.get().messages()).filteredOn(SystemMessage.class::isInstance).hasSize(1);
        assertThat(modelRequest.get().messages()).filteredOn(UserMessage.class::isInstance)
                .extracting(message -> ((UserMessage) message).singleText())
                .containsExactly("hello", "catalog instructions");
    }
}
