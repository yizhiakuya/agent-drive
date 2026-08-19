package com.agentdrive.api.automation;

import com.agentdrive.files.FileStorageService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 从 owner-scoped 文件存储中读取最近一份自动化 Markdown 报告。
 *
 * <p>服务只扫描 {@code Agent/notes} 下名称以 {@code 自动化报告-} 开头且以
 * {@code .md} 结尾的文件，按文件名自然序取最新一份，并将正文截断到 2000 字符。
 * 缺失目录、文件读取错误或存储异常被视为“暂无报告”，不会让只读报告 API 失败。
 */
@Component
@Profile({"java-auth", "java-chat"})
public final class AutomationReportService {
    private final FileStorageService files;

    /**
     * 创建自动化报告查询服务。
     *
     * @param files 提供 owner-scoped 列表和安全文件读取的存储服务。
     */
    public AutomationReportService(FileStorageService files) {
        this.files = files;
    }

    /**
     * 查询指定用户最近一份自动化报告。
     *
     * @param userId 报告所属用户 UUID。
     * @return 含 {@code last_run=null} 和 {@code report} 的映射；无报告或读取失败时 report 为 {@code null}，有报告时包含日期和最多 2000 字符正文。
     */
    public Map<String, Object> latestFor(UUID userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("last_run", null);
        Map<String, Object> report = null;
        try {
            Map<String, Object> listing = files.list(userId, "Agent/notes");
            Object rawItems = listing.get("items");
            if (rawItems instanceof List<?> items) {
                String name = items.stream()
                        .filter(Map.class::isInstance)
                        .map(item -> String.valueOf(((Map<?, ?>) item).get("name")))
                        .filter(item -> item.startsWith("自动化报告-") && item.endsWith(".md"))
                        .max(Comparator.naturalOrder())
                        .orElse(null);
                if (name != null) {
                    String path = "Agent/notes/" + name;
                    String text = Files.readString(files.fileForRead(userId, path), StandardCharsets.UTF_8);
                    String date = name.substring("自动化报告-".length(), name.length() - ".md".length());
                    report = Map.of("date", date, "text", text.length() > 2000 ? text.substring(0, 2000) : text);
                }
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            // A missing notes directory is equivalent to no automation report.
        }
        result.put("report", report);
        return result;
    }
}
