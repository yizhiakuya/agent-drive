package com.agentdrive.agent;

import com.agentdrive.skills.BuiltinSkillProvider;
import com.agentdrive.skills.DefaultSkillRegistry;
import com.agentdrive.skills.SkillDefinition;
import com.agentdrive.skills.SkillRepository;
import com.agentdrive.skills.SkillRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReadSkillToolTest {
    @Test
    void discoversAndReadsEnabledOwnerSkill() throws Exception {
        UUID owner = UUID.randomUUID();
        MemoryRepository repository = new MemoryRepository();
        BuiltinSkillProvider builtins = () -> List.of(new SkillDefinition(
                "agent-drive-api", "内置 API", "API instructions", true,
                "builtin", 1, null, null));
        SkillRegistry registry = new DefaultSkillRegistry(repository, List.of(builtins), value -> value);
        registry.save(owner, "weekly-report", "生成周报", "先读取文件，再生成周报", true);
        registry.save(owner, "disabled", "停用", "不可读取", false);
        ObjectMapper mapper = new ObjectMapper();
        ReadSkillTool tool = new ReadSkillTool(registry, mapper);
        AgentToolContext context = new AgentToolContext(owner, "request-1", List.of());

        JsonNode discovered = mapper.readTree(tool.executeRaw(
                "{\"action\":\"discover\",\"query\":\"周报\"}", context));
        JsonNode read = mapper.readTree(tool.executeRaw(
                "{\"action\":\"read\",\"name\":\"weekly-report\"}", context));
        JsonNode disabled = mapper.readTree(tool.executeRaw(
                "{\"action\":\"read\",\"name\":\"disabled\"}", context));

        assertThat(discovered.path("skills")).hasSize(1);
        assertThat(discovered.path("skills").get(0).path("name").asText()).isEqualTo("weekly-report");
        assertThat(read.path("skill").path("instructions").asText()).contains("生成周报");
        assertThat(disabled.path("ok").asBoolean()).isFalse();
        assertThat(disabled.path("status").asInt()).isEqualTo(404);
    }

    @Test
    void rejectsMissingOwnerAndExposesOneStructuredTool() throws Exception {
        SkillRegistry registry = new DefaultSkillRegistry(new MemoryRepository(), List.of(), value -> value);
        ObjectMapper mapper = new ObjectMapper();
        ReadSkillTool tool = new ReadSkillTool(registry, mapper);

        JsonNode result = mapper.readTree(tool.executeRaw("{\"action\":\"discover\"}", null));
        List<ToolSpecification> specifications = ToolSpecifications.toolSpecificationsFrom(ReadSkillTool.class);

        assertThat(result.path("status").asInt()).isEqualTo(401);
        assertThat(specifications).hasSize(1);
        assertThat(specifications.get(0).name()).isEqualTo("read_skill");
        assertThat(specifications.get(0).parameters().properties())
                .containsKeys("action", "query", "name", "offset", "limit")
                .doesNotContainKeys("userId", "authenticatedUserId");
    }

    private static final class MemoryRepository implements SkillRepository {
        private final Map<UUID, Map<String, SkillDefinition>> values = new LinkedHashMap<>();

        @Override public List<SkillDefinition> list(UUID userId) {
            return List.copyOf(values.getOrDefault(userId, Map.of()).values());
        }
        @Override public Optional<SkillDefinition> find(UUID userId, String name) {
            return Optional.ofNullable(values.getOrDefault(userId, Map.of()).get(name));
        }
        @Override public Optional<SkillDefinition> upsert(UUID userId, String name, String description,
                                                          String instructions, boolean enabled, int maxSkills) {
            Map<String, SkillDefinition> owner = values.computeIfAbsent(userId, ignored -> new LinkedHashMap<>());
            SkillDefinition skill = new SkillDefinition(name, description, instructions, enabled,
                    "custom", 1, 1.0, 1.0);
            owner.put(name, skill);
            return Optional.of(skill);
        }
        @Override public boolean delete(UUID userId, String name) {
            return values.getOrDefault(userId, Map.of()).remove(name) != null;
        }
    }
}
