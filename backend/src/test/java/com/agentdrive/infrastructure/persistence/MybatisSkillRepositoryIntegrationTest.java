package com.agentdrive.infrastructure.persistence;

import com.agentdrive.skills.SkillDefinition;
import com.agentdrive.skills.SkillRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "AGENT_DRIVE_JDBC_TEST_URL", matches = ".+")
class MybatisSkillRepositoryIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SkillRepository skills;

    @Test
    void persistsVersionsAndKeepsOwnersIsolated() {
        String firstUser = UUID.randomUUID().toString();
        String secondUser = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO users(id, username, password_hash) VALUES (?::uuid, ?, ?), (?::uuid, ?, ?)",
                    firstUser, "skill-owner-a-" + firstUser, "test-hash",
                    secondUser, "skill-owner-b-" + secondUser, "test-hash");
            UUID first = UUID.fromString(firstUser);
            UUID second = UUID.fromString(secondUser);

            SkillDefinition created = skills.upsert(first, "shared", "A", "first", true, 100).orElseThrow();
            SkillDefinition updated = skills.upsert(first, "shared", "A2", "second", false, 100).orElseThrow();
            skills.upsert(second, "shared", "B", "other", true, 100).orElseThrow();

            assertThat(created.version()).isEqualTo(1);
            assertThat(updated.version()).isEqualTo(2);
            assertThat(updated.enabled()).isFalse();
            assertThat(skills.find(first, "shared").orElseThrow().instructions()).isEqualTo("second");
            assertThat(skills.find(second, "shared").orElseThrow().instructions()).isEqualTo("other");
            assertThat(skills.delete(first, "shared")).isTrue();
            assertThat(skills.find(first, "shared")).isEmpty();
            assertThat(skills.find(second, "shared")).isPresent();
        } finally {
            jdbc.update("DELETE FROM users WHERE id IN (?::uuid, ?::uuid)", firstUser, secondUser);
        }
    }
}
