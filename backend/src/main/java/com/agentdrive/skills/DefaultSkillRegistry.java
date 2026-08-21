package com.agentdrive.skills;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 合并动态内置 Skill 和 PostgreSQL 自定义 Skill，并统一执行 owner、校验和分页规则。
 */
public final class DefaultSkillRegistry implements SkillRegistry {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;
    public static final int MAX_CUSTOM_SKILLS = 100;
    public static final int MAX_DESCRIPTION_LENGTH = 500;
    public static final int MAX_INSTRUCTIONS_LENGTH = 16_000;

    private static final Pattern NAME = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$");
    private static final Comparator<SkillDefinition> ORDER = Comparator
            .comparingInt((SkillDefinition skill) -> "builtin".equals(skill.source()) ? 0 : 1)
            .thenComparing(SkillDefinition::name);

    private final SkillRepository repository;
    private final Map<String, SkillDefinition> builtins;
    private final SkillTextSanitizer sanitizer;

    /**
     * 构造 owner Skill registry，并拒绝重复或非法的内置名称。
     * @param repository 自定义 Skill repository
     * @param providers 应用内置 Skill provider
     * @param sanitizer 自定义 Skill 文本落库前清理器
     */
    public DefaultSkillRegistry(SkillRepository repository, List<BuiltinSkillProvider> providers,
                                SkillTextSanitizer sanitizer) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer must not be null");
        Map<String, SkillDefinition> collected = new LinkedHashMap<>();
        for (BuiltinSkillProvider provider : Objects.requireNonNull(providers, "providers must not be null")) {
            for (SkillDefinition skill : Objects.requireNonNull(provider.skills(), "builtin skills must not be null")) {
                String name = normalizeName(skill.name());
                if (!"builtin".equals(skill.source())) {
                    throw new IllegalArgumentException("builtin skill source must be builtin: " + name);
                }
                if (collected.putIfAbsent(name, skill) != null) {
                    throw new IllegalArgumentException("duplicate builtin skill: " + name);
                }
            }
        }
        this.builtins = Map.copyOf(collected);
    }

    /** {@inheritDoc} */
    @Override
    public SkillPage discover(UUID userId, String query, boolean includeDisabled, Integer offset, Integer limit) {
        requireUser(userId);
        List<SkillDefinition> matches = new ArrayList<>(builtins.values());
        repository.list(userId).stream()
                .filter(skill -> includeDisabled || skill.enabled())
                .forEach(matches::add);
        matches.removeIf(skill -> !matches(skill, query));
        matches.sort(ORDER);

        int pageLimit = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        int pageOffset = Math.min(Math.max(offset == null ? 0 : offset, 0), matches.size());
        int end = Math.min(pageOffset + pageLimit, matches.size());
        List<SkillSummary> page = matches.subList(pageOffset, end).stream().map(SkillSummary::from).toList();
        return new SkillPage(page, matches.size(), page.size(), pageOffset, pageLimit,
                end < matches.size(), end);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<SkillDefinition> read(UUID userId, String name, boolean includeDisabled) {
        requireUser(userId);
        String normalized = normalizeName(name);
        SkillDefinition builtin = builtins.get(normalized);
        if (builtin != null) return Optional.of(builtin);
        return repository.find(userId, normalized).filter(skill -> includeDisabled || skill.enabled());
    }

    /** {@inheritDoc} */
    @Override
    public SkillDefinition save(UUID userId, String name, String description,
                                String instructions, boolean enabled) {
        requireUser(userId);
        String normalized = normalizeName(name);
        if (builtins.containsKey(normalized)) {
            throw new SkillRegistryException(409, "builtin_skill_read_only", "内置 Skill 不可修改");
        }
        String checkedDescription = sanitizer.sanitize(
                requireText(description, "description", MAX_DESCRIPTION_LENGTH));
        String checkedInstructions = sanitizer.sanitize(
                requireText(instructions, "instructions", MAX_INSTRUCTIONS_LENGTH));
        return repository.upsert(userId, normalized, checkedDescription, checkedInstructions,
                        enabled, MAX_CUSTOM_SKILLS)
                .orElseThrow(() -> new SkillRegistryException(
                        409, "skill_limit_reached", "每个 owner 最多创建 " + MAX_CUSTOM_SKILLS + " 个自定义 Skill"));
    }

    /** {@inheritDoc} */
    @Override
    public boolean delete(UUID userId, String name) {
        requireUser(userId);
        String normalized = normalizeName(name);
        if (builtins.containsKey(normalized)) {
            throw new SkillRegistryException(409, "builtin_skill_read_only", "内置 Skill 不可删除");
        }
        return repository.delete(userId, normalized);
    }

    /**
     * 规范化并校验 Skill slug。
     * @param value 原始名称
     * @return 小写 slug
     * @throws SkillRegistryException 名称格式非法时抛出
     */
    public static String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(normalized).matches()) {
            throw new SkillRegistryException(
                    400, "invalid_skill_name", "Skill 名称必须是 1-64 位小写字母、数字或中划线 slug");
        }
        return normalized;
    }

    /**
     * 判断 Skill 名称或说明是否匹配查询中的全部空白分词。
     * @param skill 候选 Skill
     * @param query 查询文本
     * @return 全部分词都命中时为 true
     */
    private boolean matches(SkillDefinition skill, String query) {
        if (query == null || query.isBlank()) return true;
        String haystack = (skill.name() + " " + skill.description()).toLowerCase(Locale.ROOT);
        for (String term : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!term.isBlank() && !haystack.contains(term)) return false;
        }
        return true;
    }

    /**
     * 校验 owner 不为空。
     * @param userId 当前 owner
     * @throws SkillRegistryException owner 缺失时抛出
     */
    private void requireUser(UUID userId) {
        if (userId == null) throw new SkillRegistryException(401, "missing_owner", "缺少认证 owner");
    }

    /**
     * 规范化并限制用户可编辑文本。
     * @param value 原始文本
     * @param field 错误码字段名
     * @param maxLength 最大字符数
     * @return trim 后文本
     * @throws SkillRegistryException 文本为空或超限时抛出
     */
    private String requireText(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new SkillRegistryException(400, "invalid_skill_" + field, field + " 不能为空");
        }
        if (normalized.length() > maxLength) {
            throw new SkillRegistryException(400, "invalid_skill_" + field,
                    field + " 不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }
}
