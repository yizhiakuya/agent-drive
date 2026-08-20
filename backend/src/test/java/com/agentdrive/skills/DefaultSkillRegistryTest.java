package com.agentdrive.skills;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultSkillRegistryTest {
    private final UUID owner = UUID.randomUUID();
    private MemoryRepository repository;
    private SkillRegistry registry;

    @BeforeEach
    void setUp() {
        repository = new MemoryRepository();
        BuiltinSkillProvider provider = () -> List.of(new SkillDefinition(
                "agent-drive-api", "内置 API", "使用 backend_api", true,
                "builtin", 1, null, null));
        registry = new DefaultSkillRegistry(repository, List.of(provider), value -> value
                .replaceAll("sk-[A-Za-z0-9_-]+", "[REDACTED]")
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._-]+", "Bearer [REDACTED]"));
    }

    @Test
    void mergesBuiltinAndOwnerSkillsWhileHidingDisabledFromAgentDiscovery() {
        registry.save(owner, "notes", "整理笔记", "按主题整理", true);
        registry.save(owner, "disabled", "停用技能", "不应发现", false);

        SkillPage enabled = registry.discover(owner, "", false, 0, 20);
        SkillPage all = registry.discover(owner, "", true, 0, 20);

        assertThat(enabled.skills()).extracting(SkillSummary::name)
                .containsExactly("agent-drive-api", "notes");
        assertThat(all.skills()).extracting(SkillSummary::name)
                .containsExactly("agent-drive-api", "disabled", "notes");
        assertThat(registry.read(owner, "disabled", false)).isEmpty();
        assertThat(registry.read(owner, "disabled", true)).isPresent();
    }

    @Test
    void savesVersionsDeletesAndKeepsOwnersIsolated() {
        UUID other = UUID.randomUUID();
        SkillDefinition first = registry.save(owner, "weekly-report", "周报", "生成周报", true);
        SkillDefinition second = registry.save(owner, "weekly-report", "周报", "生成完整周报", true);
        registry.save(other, "weekly-report", "其他周报", "其他 owner", true);

        assertThat(first.version()).isEqualTo(1);
        assertThat(second.version()).isEqualTo(2);
        assertThat(registry.read(owner, "weekly-report", true).orElseThrow().instructions())
                .isEqualTo("生成完整周报");
        assertThat(registry.read(other, "weekly-report", true).orElseThrow().instructions())
                .isEqualTo("其他 owner");
        assertThat(registry.delete(owner, "weekly-report")).isTrue();
        assertThat(registry.read(owner, "weekly-report", true)).isEmpty();
        assertThat(registry.read(other, "weekly-report", true)).isPresent();
    }

    @Test
    void rejectsBuiltinMutationInvalidContentAndQuotaOverflow() {
        assertThatThrownBy(() -> registry.save(owner, "agent-drive-api", "覆盖", "覆盖", true))
                .isInstanceOf(SkillRegistryException.class)
                .extracting(error -> ((SkillRegistryException) error).code())
                .isEqualTo("builtin_skill_read_only");
        assertThatThrownBy(() -> registry.save(owner, "Bad Name", "说明", "内容", true))
                .isInstanceOf(SkillRegistryException.class);
        assertThatThrownBy(() -> registry.save(owner, "valid", "", "内容", true))
                .isInstanceOf(SkillRegistryException.class);

        repository.rejectWrites = true;
        assertThatThrownBy(() -> registry.save(owner, "quota", "说明", "内容", true))
                .isInstanceOf(SkillRegistryException.class)
                .extracting(error -> ((SkillRegistryException) error).code())
                .isEqualTo("skill_limit_reached");
    }

    @Test
    void paginatesAndSearchesNameAndDescription() {
        registry.save(owner, "alpha", "文件整理", "A", true);
        registry.save(owner, "beta", "任务整理", "B", true);
        registry.save(owner, "gamma", "文件归档", "C", true);

        SkillPage first = registry.discover(owner, "文件", false, 0, 1);
        SkillPage second = registry.discover(owner, "文件", false, first.nextOffset(), 1);

        assertThat(first.totalMatches()).isEqualTo(2);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.skills()).extracting(SkillSummary::name).containsExactly("alpha");
        assertThat(second.hasMore()).isFalse();
        assertThat(second.skills()).extracting(SkillSummary::name).containsExactly("gamma");
    }

    @Test
    void sanitizesCredentialsBeforePersistence() {
        String apiKey = "sk-" + "secretvalue";
        String bearer = "Bearer " + "token-value";
        SkillDefinition saved = registry.save(owner, "safe", "调用 " + apiKey, bearer, true);

        assertThat(saved.description()).contains("[REDACTED]").doesNotContain(apiKey);
        assertThat(saved.instructions()).doesNotContain(bearer);
    }

    private static final class MemoryRepository implements SkillRepository {
        private final Map<UUID, Map<String, SkillDefinition>> skills = new LinkedHashMap<>();
        private boolean rejectWrites;

        @Override
        public List<SkillDefinition> list(UUID userId) {
            return new ArrayList<>(skills.getOrDefault(userId, Map.of()).values());
        }

        @Override
        public Optional<SkillDefinition> find(UUID userId, String name) {
            return Optional.ofNullable(skills.getOrDefault(userId, Map.of()).get(name));
        }

        @Override
        public Optional<SkillDefinition> upsert(UUID userId, String name, String description,
                                                String instructions, boolean enabled, int maxSkills) {
            if (rejectWrites) return Optional.empty();
            Map<String, SkillDefinition> owner = skills.computeIfAbsent(userId, ignored -> new LinkedHashMap<>());
            SkillDefinition previous = owner.get(name);
            SkillDefinition saved = new SkillDefinition(name, description, instructions, enabled, "custom",
                    previous == null ? 1 : previous.version() + 1,
                    previous == null ? 1.0 : previous.createdAt(), 2.0);
            owner.put(name, saved);
            return Optional.of(saved);
        }

        @Override
        public boolean delete(UUID userId, String name) {
            return skills.getOrDefault(userId, Map.of()).remove(name) != null;
        }
    }
}
