package com.agentdrive.infrastructure.persistence;

import com.agentdrive.infrastructure.persistence.mapper.SkillMapper;
import com.agentdrive.skills.SkillDefinition;
import com.agentdrive.skills.SkillRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 将 MyBatis Skill 行转换为 owner-scoped Skill repository。 */
public final class MybatisSkillRepository implements SkillRepository {
    private final SkillMapper mapper;

    /**
     * 创建 Skill repository。
     * @param mapper 自定义 Skill Mapper
     */
    public MybatisSkillRepository(SkillMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public List<SkillDefinition> list(UUID userId) {
        requireUser(userId);
        return mapper.selectAll(userId.toString()).stream().map(this::definition).toList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<SkillDefinition> find(UUID userId, String name) {
        requireUser(userId);
        return Optional.ofNullable(mapper.selectByName(userId.toString(), name)).map(this::definition);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public Optional<SkillDefinition> upsert(UUID userId, String name, String description,
                                            String instructions, boolean enabled, int maxSkills) {
        requireUser(userId);
        return Optional.ofNullable(mapper.upsert(
                userId.toString(), name, description, instructions, enabled, maxSkills)).map(this::definition);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public boolean delete(UUID userId, String name) {
        requireUser(userId);
        return mapper.delete(userId.toString(), name) > 0;
    }

    /**
     * 把数据库字段映射为自定义 Skill。
     * @param row MyBatis Skill 行
     * @return 自定义 Skill 定义
     */
    private SkillDefinition definition(Map<String, Object> row) {
        return new SkillDefinition(
                String.valueOf(row.get("name")),
                String.valueOf(row.get("description")),
                String.valueOf(row.get("instructions")),
                Boolean.TRUE.equals(row.get("enabled")),
                "custom",
                intValue(row.get("version")),
                doubleValue(row.get("created_at")),
                doubleValue(row.get("updated_at"))
        );
    }

    /**
     * 校验 owner。
     * @param userId 当前 owner
     * @throws IllegalArgumentException owner 缺失时抛出
     */
    private void requireUser(UUID userId) {
        if (userId == null) throw new IllegalArgumentException("userId must not be null");
    }

    /**
     * 转换数据库整数。
     * @param value Number 或数字文本
     * @return int 值
     */
    private int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    /**
     * 转换数据库 epoch 秒。
     * @param value Number、数字文本或 null
     * @return double epoch 秒；空值返回 null
     */
    private Double doubleValue(Object value) {
        if (value == null) return null;
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value));
    }
}
