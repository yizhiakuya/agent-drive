package com.agentdrive.infrastructure.persistence;

/**
 * 为 PostgreSQL {@code LIKE} 前缀查询转义用户路径中的通配符。
 *
 * <p>路径是用户数据，不能直接拼接到 {@code LIKE} 表达式，否则合法的百分号、
 * 下划线或反斜杠会改变匹配范围。调用方应将返回值与 {@code ESCAPE CHR(92)}
 * 一起使用。</p>
 */
public final class SqlLikeEscaper {
    private SqlLikeEscaper() {
    }

    /**
     * 转义 PostgreSQL LIKE 的转义符及两个通配符。
     *
     * @param value 用户提供的路径或前缀；空值按空字符串处理。
     * @return 可安全放入 LIKE 模式的文本。
     */
    public static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
