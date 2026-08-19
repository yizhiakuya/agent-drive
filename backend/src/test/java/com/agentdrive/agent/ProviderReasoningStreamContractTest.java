package com.agentdrive.agent;

import com.agentdrive.api.chat.ChatRequest;
import com.agentdrive.api.chat.ChatSseEvent;
import com.agentdrive.api.chat.LangChainAgentRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAI/OpenAI 兼容协议思考过程回归测试：
 * langchain4j 的 OpenAiStreamingChatModel 只在构建期 returnThinking=true 时才把
 * chat/completions 流中的 reasoning_content delta 转成 onPartialThinking /
 * AiMessage.thinking()；默认 false 会静默丢弃，导致思考过程既不进 SSE 也不落库。
 * 本测试用本地 SSE stub 服务器钉死「reasoning_content 必须流到 reasoning 事件」的契约。
 */
class ProviderReasoningStreamContractTest {

    @Test
    void openAiCompatibleReasoningContentSurfacesAsReasoningSseEvents() throws Exception {
        List<String> requestBodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            String[] chunks = {
                    chunk("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                            + "\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,"
                            + "\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}"),
                    chunk("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                            + "\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,"
                            + "\"delta\":{\"reasoning_content\":\"先\"},\"finish_reason\":null}]}"),
                    chunk("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                            + "\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,"
                            + "\"delta\":{\"reasoning_content\":\"判断范围\"},\"finish_reason\":null}]}"),
                    chunk("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                            + "\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,"
                            + "\"delta\":{\"content\":\"结果如下\"},\"finish_reason\":null}]}"),
                    chunk("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                            + "\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,"
                            + "\"delta\":{},\"finish_reason\":\"stop\"}]}"),
                    "data: [DONE]\n\n"
            };
            for (String part : chunks) {
                out.write(part.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            out.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            LlmProviderConfig config = new LlmProviderConfig(
                    LlmProviderConfig.ProviderType.OPENAI_COMPATIBLE, "test-key", baseUrl, "deepseek-chat"
            );
            StreamingModelFactory factory = new StreamingModelFactory();
            StreamingChatModel model = factory.create(config);

            // (1) 模型层：reasoning_content delta 必须到达 onPartialThinking
            List<String> partialThinking = new ArrayList<>();
            List<String> partialText = new ArrayList<>();
            CountDownLatch complete = new CountDownLatch(1);
            StreamingChatResponseHandler handler = new StreamingChatResponseHandler() {
                @Override
                public void onPartialThinking(PartialThinking thinking) {
                    if (thinking != null && thinking.text() != null && !thinking.text().isEmpty()) {
                        partialThinking.add(thinking.text());
                    }
                }

                @Override
                public void onPartialResponse(String text) {
                    if (text != null && !text.isEmpty()) {
                        partialText.add(text);
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    complete.countDown();
                }

                @Override
                public void onError(Throwable error) {
                    complete.countDown();
                }
            };
            ChatRequestParameters parameters = OpenAiChatRequestParameters.builder()
                    .reasoningEffort("high")
                    .build();
            model.chat(dev.langchain4j.model.chat.request.ChatRequest.builder()
                    .messages(List.of(UserMessage.from("分析文件")))
                    .parameters(parameters)
                    .build(), handler);
            assertThat(complete.await(5, TimeUnit.SECONDS)).as("OpenAI 流必须完成").isTrue();
            assertThat(partialThinking).containsExactly("先", "判断范围");
            assertThat(partialText).containsExactly("结果如下");

            // (2) 全链路：runtime 必须把 reasoning 作为独立 SSE 事件发出，text 随后，done 收尾
            LangChainAgentRuntime runtime = new LangChainAgentRuntime(
                    model,
                    new BackendApiTool(new OperationCatalog(List.of()),
                            (operation, request) -> Map.of(), new ObjectMapper()),
                    new ObjectMapper(),
                    factory.requestFactory(config),
                    "system",
                    4
            );
            List<ChatSseEvent> events = runtime.stream(new ChatRequest(
                    "分析文件", null, null, null, "high"
            )).collectList().block(Duration.ofSeconds(5));
            assertThat(events).isNotNull();
            assertThat(events).extracting(ChatSseEvent::event)
                    .containsExactly("reasoning", "reasoning", "text", "done");
            assertThat(events.get(0).data().get("text")).isEqualTo("先");
            assertThat(events.get(1).data().get("text")).isEqualTo("判断范围");
            assertThat(events.get(2).data().get("text")).isEqualTo("结果如下");

            // (3) 显式等级必须映射到 wire 上的 reasoning_effort（body 是 pretty JSON，先归一化空白）
            assertThat(requestBodies).anyMatch(body ->
                    body.replaceAll("\\s+", "").contains("\"reasoning_effort\":\"high\""));
        } finally {
            server.stop(0);
        }
    }

    private static String chunk(String json) {
        return "data: " + json + "\n\n";
    }
}
