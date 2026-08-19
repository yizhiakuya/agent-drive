package com.agentdrive.api.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 将 {@link ChatSseEvent} 编码为符合浏览器 SSE 协议的文本帧。
 *
 * <p>每帧写出一个 event 行、一个 JSON object data 行和两个换行；JSON 序列化失败
 * 被视为服务端协议错误并转换为 {@link IllegalStateException}。
 */
@Component
public class ChatSseEncoder {
    private final ObjectMapper objectMapper;

    /**
     * 创建 SSE 编码器。
     *
     * @param objectMapper 将事件 data 序列化为 JSON 的映射器。
     */
    public ChatSseEncoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 编码一个完整 SSE 事件帧。
     *
     * @param event 已通过事件模型校验的事件。
     * @return 以 UTF-8 文本表示的 {@code event/data} 帧，并以空行结束。
     * @throws IllegalStateException data 无法序列化时抛出。
     */
    public String encode(ChatSseEvent event) {
        try {
            return "event: " + event.event() + "\ndata: "
                    + objectMapper.writeValueAsString(event.data()) + "\n\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to encode chat SSE event", e);
        }
    }
}
