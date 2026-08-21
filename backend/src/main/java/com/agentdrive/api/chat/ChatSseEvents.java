package com.agentdrive.api.chat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 创建聊天 SSE 协议规定的标准事件。
 *
 * <p>所有方法都返回 data 为 JSON 对象的 {@link ChatSseEvent}，避免前端解析器收到
 * 裸字符串；工具输出展示最多保留 500 个字符，但 parsed 字段保持完整解析结果。
 */
public final class ChatSseEvents {
    /**
     * 禁止实例化静态事件工厂。
     */
    private ChatSseEvents() {
    }

    /**
     * 创建助手正文增量事件。
     *
     * @param text 本次流产生的正文片段；空值编码为空字符串。
     * @return {@code event=text, data={text: ...}} 事件。
     */
    public static ChatSseEvent text(String text) {
        return new ChatSseEvent("text", Map.of("text", text == null ? "" : text));
    }

    /**
     * 创建模型思考过程增量事件。
     *
     * @param text 本次流产生的 reasoning 片段；不会并入正文 text 事件。
     * @return {@code event=reasoning, data={text: ...}} 事件。
     */
    public static ChatSseEvent reasoning(String text) {
        return new ChatSseEvent("reasoning", Map.of("text", text == null ? "" : text));
    }

    /**
     * 创建可由前端折叠展示的上下文注入事件。
     * @param context 已校验的上下文快照
     * @return 包含来源、类型和完整正文的 {@code context} 事件
     */
    public static ChatSseEvent context(ChatContext context) {
        return new ChatSseEvent("context", Map.of(
                "source", context.source(),
                "kind", context.kind(),
                "content", context.content()
        ));
    }

    /**
     * 创建默认步骤号为 0 的工具开始事件。
     *
     * @param tool 工具名称。
     * @param arguments 模型提交的工具参数；空值编码为空对象。
     * @return 委托给带 step 重载创建的 tool_start 事件。
     */
    public static ChatSseEvent toolStart(String tool, Map<String, Object> arguments) {
        return toolStart(0, tool, arguments);
    }

    /**
     * 创建包含 Agent 步骤号和工具参数的工具开始事件。
     *
     * @param step Agent 当前执行步数。
     * @param tool 工具名称；空值编码为空字符串。
     * @param arguments 工具调用参数；空值编码为空对象。
     * @return {@code event=tool_start} 事件。
     */
    public static ChatSseEvent toolStart(int step, String tool, Map<String, Object> arguments) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("step", step);
        data.put("tool", tool == null ? "" : tool);
        data.put("arguments", arguments == null ? Map.of() : arguments);
        return new ChatSseEvent("tool_start", data);
    }

    /**
     * 将已有工具轨迹映射为 tool_trace 事件。
     *
     * @param trace 已由调用方整理的工具轨迹字段；空值编码为空对象。
     * @return {@code event=tool_trace} 事件。
     */
    public static ChatSseEvent toolTrace(Map<String, Object> trace) {
        return new ChatSseEvent("tool_trace", trace == null ? Map.of() : trace);
    }

    /**
     * 创建带展示截断标记的工具执行轨迹事件。
     *
     * @param step Agent 当前执行步数。
     * @param tool 工具名称。
     * @param arguments 工具参数。
     * @param output 工具原始输出；展示字段最多保留 500 个字符。
     * @param parsed 从完整输出解析出的结构化结果，可为空。
     * @param outputTruncated 调用方是否已经知道输出被截断。
     * @param replayed 是否命中确定性工具重放。
     * @return 包含工具输入、展示输出、解析结果和截断/重放标记的事件。
     */
    public static ChatSseEvent toolTrace(int step, String tool, Map<String, Object> arguments,
                                         String output, Map<String, Object> parsed,
                                         boolean outputTruncated, boolean replayed) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("step", step);
        data.put("tool", tool == null ? "" : tool);
        data.put("arguments", arguments == null ? Map.of() : arguments);
        String displayOutput = output == null ? "" : output;
        boolean truncated = outputTruncated || displayOutput.length() > 500;
        data.put("output", displayOutput.length() > 500 ? displayOutput.substring(0, 500) : displayOutput);
        if (parsed != null) {
            data.put("parsed", parsed);
        }
        data.put("output_truncated", truncated);
        if (replayed) {
            data.put("replayed", true);
        }
        return new ChatSseEvent("tool_trace", data);
    }

    /**
     * 创建由模型请求、交给浏览器执行的语义界面动作事件。
     *
     * @param action 已由前端动作工具校验的动作对象，包含 operation 和 arguments
     * @return {@code event=frontend_action} 事件
     */
    public static ChatSseEvent frontendAction(Map<String, Object> action) {
        return new ChatSseEvent("frontend_action", action == null ? Map.of() : action);
    }

    /**
     * 将聊天完成结果转换为 done 事件。
     *
     * @param response 聊天完成结果；空值编码为空数据对象。
     * @return 由 {@link ChatResponse#doneData()} 生成的 done 事件。
     */
    public static ChatSseEvent done(ChatResponse response) {
        return done(response == null ? Map.of() : response.doneData());
    }

    /**
     * 创建任意完成数据的 done 事件。
     *
     * @param response 已整理的完成数据；空值编码为空对象。
     * @return {@code event=done} 事件。
     */
    public static ChatSseEvent done(Map<String, Object> response) {
        return new ChatSseEvent("done", response == null ? Map.of() : response);
    }

    /**
     * 创建流内错误事件。
     *
     * @param message 面向客户端的错误消息；空值编码为空字符串。
     * @return {@code event=error, data={error: ...}} 事件。
     */
    public static ChatSseEvent error(String message) {
        return error(message, null);
    }

    /**
     * 创建携带当前会话 ID 的流内错误事件。
     *
     * @param message 面向客户端的错误消息；空值编码为空字符串。
     * @param sessionId 已由服务端确认的会话 ID，可为空。
     * @return {@code event=error, data={error: ..., session_id?: ...}} 事件。
     */
    public static ChatSseEvent error(String message, String sessionId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("error", message == null ? "" : message);
        if (sessionId != null && !sessionId.isBlank()) {
            data.put("session_id", sessionId);
        }
        return new ChatSseEvent("error", data);
    }
}
