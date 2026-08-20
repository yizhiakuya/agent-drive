package com.agentdrive.agent;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理 Agent 可见的后端 operation 定义及其发现结果。
 *
 * <p>构造时按 operation 名去重并冻结列表和索引；discover 采用 operation、HTTP 方法、
 * 路径及摘要的包含匹配，再按稳定顺序分页。中文别名会先转换为稳定的英文检索词，供模型
 * 从自然语言找到精确 operation。</p>
 */
public final class OperationCatalog {
    public static final int DISCOVERY_LIMIT = 6;
    public static final int MAX_DISCOVERY_LIMIT = 20;

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("文件", "file"),
            Map.entry("目录", "file"),
            Map.entry("后端", "api"),
            Map.entry("接口", "api"),
            Map.entry("操作", "api"),
            Map.entry("上传", "upload"),
            Map.entry("移动", "move"),
            Map.entry("复制", "copy"),
            Map.entry("删除", "delete"),
            Map.entry("配置", "config"),
            Map.entry("模型", "model"),
            Map.entry("任务", "task"),
            Map.entry("索引", "index"),
            Map.entry("记忆", "memory"),
            Map.entry("搜索", "search"),
            Map.entry("规则", "rule"),
            Map.entry("偏好", "preference"),
            Map.entry("审计", "audit"),
            Map.entry("创建", "create"),
            Map.entry("查询", "query"),
            Map.entry("设置", "setting")
    );

    private final List<OperationDefinition> operations;
    private final Map<String, OperationDefinition> byName;

    /**
     * 去重并冻结 operation 集合。
     * @param operations 已登记的 operation；同名项保留后出现的定义
     * @throws NullPointerException operations 为空时抛出
     */
    public OperationCatalog(Collection<OperationDefinition> operations) {
        Objects.requireNonNull(operations, "operations must not be null");
        Map<String, OperationDefinition> unique = operations.stream()
                .collect(Collectors.toMap(
                        OperationDefinition::operation,
                        Function.identity(),
                        (left, right) -> right,
                        LinkedHashMap::new
                ));
        this.byName = Map.copyOf(unique);
        this.operations = List.copyOf(unique.values());
    }

    /**
     * 按精确名称查找 operation。
     * @param operation operation 名称
     * @return 找到的定义；名称未登记时为空
     */
    public Optional<OperationDefinition> find(String operation) {
        return Optional.ofNullable(byName.get(operation));
    }

    /**
     * 根据自然语言查询返回默认大小的第一页 operation。
     * @param query 用户或模型的检索词，可为空
     * @return 排序后的第一页候选 operation
     */
    public List<OperationDefinition> discover(String query) {
        return discover(query, 0, DISCOVERY_LIMIT).operations();
    }

    /**
     * 根据自然语言查询和分页窗口返回稳定的 operation 候选页。
     *
     * <p>空查询保留登记顺序；非空查询按命中词计分，operation 名命中权重更高，
     * 同分时按 operation 名排序。偏移小于 0 时按 0 处理，页大小限制在 1 到
     * {@link #MAX_DISCOVERY_LIMIT}，空值或非正数使用默认值。</p>
     * @param query 用户或模型的检索词，可为空
     * @param offset 匹配结果起始偏移，可为空
     * @param limit 期望单页数量，可为空
     * @return 包含总数、实际窗口和下一偏移的发现页
     */
    public DiscoveryPage discover(String query, Integer offset, Integer limit) {
        List<OperationDefinition> matches = matches(query);
        int pageLimit = limit == null || limit <= 0
                ? DISCOVERY_LIMIT
                : Math.min(limit, MAX_DISCOVERY_LIMIT);
        int pageOffset = Math.min(Math.max(offset == null ? 0 : offset, 0), matches.size());
        int end = Math.min(pageOffset + pageLimit, matches.size());
        return new DiscoveryPage(
                matches.subList(pageOffset, end),
                matches.size(),
                pageOffset,
                pageLimit,
                end < matches.size(),
                end
        );
    }

    /**
     * 计算完整且稳定排序的 discover 匹配集。
     * @param query 用户或模型的检索词，可为空
     * @return 未分页的不可变匹配列表
     */
    private List<OperationDefinition> matches(String query) {
        if (query == null || query.isBlank()) {
            return operations;
        }
        List<String> terms = terms(query);
        return operations.stream()
                .map(operation -> Map.entry(operation, score(operation, terms)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<OperationDefinition, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(entry -> entry.getKey().operation()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 保存一页 discover 结果及继续读取所需的稳定游标元数据。
     * @param operations 当前页 operation
     * @param totalMatches 完整匹配数量
     * @param offset 当前页实际起始偏移
     * @param limit 当前页规范化大小
     * @param hasMore 当前页之后是否仍有匹配项
     * @param nextOffset 下一页起始偏移；末页等于 totalMatches
     */
    public record DiscoveryPage(List<OperationDefinition> operations,
                                int totalMatches,
                                int offset,
                                int limit,
                                boolean hasMore,
                                int nextOffset) {
        /**
         * 冻结当前页列表，避免目录构造后被调用方修改。
         * @param operations 当前页 operation
         * @param totalMatches 完整匹配数量
         * @param offset 当前页实际起始偏移
         * @param limit 当前页规范化大小
         * @param hasMore 当前页之后是否仍有匹配项
         * @param nextOffset 下一页起始偏移
         */
        public DiscoveryPage {
            operations = List.copyOf(operations);
        }
    }

    /**
     * 为未知 operation 名生成最多六个可选名称。
     * @param operation 模型提交的名称片段；为空时返回目录前六项
     * @return operation 名或摘要包含该片段的候选名称
     */
    public List<String> suggestions(String operation) {
        if (operation == null || operation.isBlank()) {
            return operations.stream().limit(DISCOVERY_LIMIT).map(OperationDefinition::operation).toList();
        }
        String query = operation.toLowerCase(Locale.ROOT);
        return operations.stream()
                .filter(candidate -> candidate.operation().toLowerCase(Locale.ROOT).contains(query)
                        || candidate.summary().toLowerCase(Locale.ROOT).contains(query))
                .limit(DISCOVERY_LIMIT)
                .map(OperationDefinition::operation)
                .toList();
    }

    /**
     * 根据协议和路径推导默认风险级别。
     *
     * <p>内部操作为 red；GET 和模型列表探测为 green；测试或模型相关探测为 yellow；
     * 其余可能改变状态的操作为 red。</p>
     * @param method HTTP 方法或 {@code INTERNAL}
     * @param path HTTP 路径
     * @return {@code green}、{@code yellow} 或 {@code red}
     */
    public static String riskFor(String method, String path) {
        if ("INTERNAL".equals(method)) {
            return "red";
        }
        String normalizedPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if ("GET".equals(method) || ("POST".equals(method) && normalizedPath.endsWith("/models"))) {
            return "green";
        }
        if (normalizedPath.contains("/test") || normalizedPath.endsWith("/models")) {
            return "yellow";
        }
        return "red";
    }

    /**
     * 统计一个 operation 命中检索词的次数。
     * @param operation 待评分的定义
     * @param terms 已规范化的检索词
     * @return 命中分数；operation 名命中每词加三分，其余字段命中加一分
     */
    private static int score(OperationDefinition operation, List<String> terms) {
        String haystack = (operation.operation() + " " + operation.method() + " "
                + operation.path() + " " + operation.summary()).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (haystack.contains(term)) {
                score += operation.operation().toLowerCase(Locale.ROOT).contains(term) ? 3 : 1;
            }
        }
        return score;
    }

    /**
     * 把查询拆成去重的小写检索词，并将中文业务词映射为目录使用的英文词。
     * @param query 原始查询文本
     * @return 用于包含匹配的检索词列表
     */
    private static List<String> terms(String query) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String normalized = query.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> alias : ALIASES.entrySet()) {
            if (normalized.contains(alias.getKey())) {
                result.add(alias.getValue());
            }
        }
        for (String raw : query.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!raw.isBlank()) {
                result.add(ALIASES.getOrDefault(raw, raw));
            }
        }
        return List.copyOf(result);
    }
}
