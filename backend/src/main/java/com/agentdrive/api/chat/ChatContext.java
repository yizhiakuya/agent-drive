package com.agentdrive.api.chat;

import java.util.Objects;

/**
 * 一条带来源标识的模型上下文注入。
 *
 * @param source 面向用户展示的稳定来源名称
 * @param kind 上下文类型，例如 system、agent-instructions 或 skill-catalog
 * @param content 模型实际读取且可在会话中展开的完整文本
 * @param userMessage 是否额外作为 user role 消息加入模型请求
 * @param trust 信任等级；UNTRUSTED_DATA 只作为不可执行文件数据注入
 */
public record ChatContext(String source, String kind, String content, boolean userMessage,
                          Trust trust) {
    /** 兼容旧调用方；用户消息默认按 user data 处理，非用户消息按 system 处理。 */
    public ChatContext(String source, String kind, String content, boolean userMessage) {
        this(source, kind, content, userMessage,
                userMessage ? Trust.USER_DATA : Trust.SYSTEM);
    }

    /** 校验上下文来源、类型和正文均非空。 */
    public ChatContext {
        source = requireText(source, "source");
        kind = requireText(kind, "kind");
        content = requireText(content, "content");
        trust = trust == null ? (userMessage ? Trust.USER_DATA : Trust.SYSTEM) : trust;
    }

    /** 返回替换正文但保留来源和信任级别的快照。 */
    public ChatContext withContent(String nextContent) {
        return new ChatContext(source, kind, nextContent, userMessage, trust);
    }

    /** 文件/用户选择的外部正文必须按数据而不是指令处理。 */
    public boolean isUntrustedData() {
        return trust == Trust.UNTRUSTED_DATA;
    }

    /** 上下文的信任等级，仅用于编排和审计，不授予任何工具权限。 */
    public enum Trust {
        SYSTEM,
        INSTRUCTION,
        USER_DATA,
        UNTRUSTED_DATA
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
