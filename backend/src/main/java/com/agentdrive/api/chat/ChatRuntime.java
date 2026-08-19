package com.agentdrive.api.chat;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 聊天 Agent runtime 的 API 边界。
 *
 * <p>实现负责根据认证 owner、会话状态和 Provider 配置执行一次聊天；complete 返回
 * 聚合结果，stream 按正文、reasoning、工具轨迹、done/error 事件增量发出。调用方
 * 不应把客户端提供的 owner 字段当作可信身份，认证 owner 由控制器注入请求。
 */
public interface ChatRuntime {
    /**
     * 执行一次非流式聊天并聚合最终回复、工具轨迹和执行统计。
     *
     * @param request 已由控制器补齐会话 ID 和认证 owner 的聊天请求。
     * @return 完成结果；实现应在结果中反映待确认、路由和截断状态。
     */
    Mono<ChatResponse> complete(ChatRequest request);

    /**
     * 以 SSE 事件流执行一次聊天。
     *
     * @param request 已由控制器补齐会话 ID 和认证 owner 的聊天请求。
     * @return 按协议顺序发出文本、思考、工具、完成或错误事件的流。
     */
    Flux<ChatSseEvent> stream(ChatRequest request);
}
