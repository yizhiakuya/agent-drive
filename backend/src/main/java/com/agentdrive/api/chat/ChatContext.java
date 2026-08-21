package com.agentdrive.api.chat;

import java.util.Objects;

/**
 * 一条带来源标识的模型上下文注入。
 *
 * @param source 面向用户展示的稳定来源名称
 * @param kind 上下文类型，例如 system、agent-instructions 或 skill-catalog
 * @param content 模型实际读取且可在会话中展开的完整文本
 * @param userMessage 是否额外作为 user role 消息加入模型请求
 */
public record ChatContext(String source, String kind, String content, boolean userMessage) {
    /** 校验上下文来源、类型和正文均非空。 */
    public ChatContext {
        source = requireText(source, "source");
        kind = requireText(kind, "kind");
        content = requireText(content, "content");
    }

    /**
     * 校验上下文字段。
     * @param value 原始字段值
     * @param field 字段名
     * @return 去除首尾空白的文本
     */
    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
