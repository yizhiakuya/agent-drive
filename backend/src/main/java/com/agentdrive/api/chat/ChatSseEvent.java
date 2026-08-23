package com.agentdrive.api.chat;

import java.util.Map;
import java.util.Objects;

/**
 * 聊天 SSE 的内部事件模型。
 *
 * <p>事件名映射到协议中的 {@code event:} 行，data 映射到 JSON 对象；构造器拒绝空
 * 事件名并复制 data，保证事件进入响应流后不会被调用方修改。
 */
public record ChatSseEvent(String event, Map<String, Object> data) {
    /**
     * 校验并冻结一个聊天 SSE 事件。
     *
     * @param event SSE 事件名，例如 text、reasoning、tool_progress、tool_trace、done 或 error。
     * @param data 事件 JSON 对象，不能为 {@code null}。
     * @throws IllegalArgumentException 事件名为空时抛出。
     * @throws NullPointerException data 为 {@code null} 时抛出。
     */
    public ChatSseEvent {
        if (event == null || event.isBlank()) {
            throw new IllegalArgumentException("SSE event name must not be blank");
        }
        data = Map.copyOf(Objects.requireNonNull(data, "SSE data must not be null"));
    }
}
