package com.agentdrive.infrastructure;

import com.agentdrive.agent.ChatRequestFactory;
import com.agentdrive.agent.ConfiguredChatModel;
import com.agentdrive.agent.ProviderRuntimeResolver;
import com.agentdrive.agent.ThinkingLevel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AiSessionTitleGeneratorTest {
    @Test
    void usesOwnerModelAndPersistsOnlyNormalizedResponseText() {
        UUID userId = UUID.randomUUID();
        AtomicReference<ChatRequest> capturedRequest = new AtomicReference<>();
        AtomicReference<ThinkingLevel> capturedThinkingLevel = new AtomicReference<>();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                capturedRequest.set(request);
                handler.onPartialThinking(new PartialThinking("不要保存思考"));
                handler.onPartialResponse("标题：整理");
                handler.onPartialResponse("工作文件");
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from("标题：整理工作文件"))
                        .build());
            }
        };
        ChatRequestFactory requestFactory = (messages, tools, thinkingLevel) -> {
            capturedThinkingLevel.set(thinkingLevel);
            return ChatRequest.builder().messages(messages).build();
        };
        ProviderRuntimeResolver resolver = ignored -> new ConfiguredChatModel(model, requestFactory);

        String title = new AiSessionTitleGenerator(resolver).generate(userId, List.of(
                Map.of("role", "user", "content", "帮我整理工作目录中的文件"),
                Map.of("role", "assistant", "content", "我会按类型整理")
        ));

        assertThat(title).isEqualTo("整理工作文件");
        assertThat(capturedThinkingLevel).hasValue(ThinkingLevel.AUTO);
        assertThat(capturedRequest).hasValueSatisfying(request -> assertThat(request.messages()).hasSize(2));
        assertThat(capturedRequest.get().messages().get(1).toString()).contains("帮我整理工作目录中的文件");
    }
}
