package com.agentdrive.infrastructure.persistence;

import com.agentdrive.infrastructure.EmbeddingConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "AGENT_DRIVE_JDBC_TEST_URL", matches = ".+")
class MybatisEmbeddingConfigStoreIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EmbeddingConfigStore configs;

    @Test
    void persistsOwnerScopedEmbeddingConfiguration() {
        UUID owner = UUID.randomUUID();
        byte[] key = "embedding-test-key".getBytes(StandardCharsets.UTF_8);
        try {
            jdbc.update("INSERT INTO users(id, username, password_hash) VALUES (?::uuid, ?, ?)",
                    owner, "embedding-integration-" + owner, "test-hash");
            configs.save(owner, new EmbeddingConfigStore.EmbeddingConfig(
                    "jina", "https://api.jina.ai/v1", "jina-embeddings-v3", key));
            EmbeddingConfigStore.EmbeddingConfig loaded = configs.find(owner).orElseThrow();
            assertThat(loaded.provider()).isEqualTo("jina");
            assertThat(loaded.baseUrl()).isEqualTo("https://api.jina.ai/v1");
            assertThat(loaded.model()).isEqualTo("jina-embeddings-v3");
            assertThat(loaded.encryptedApiKey()).containsExactly(key);
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?::uuid", owner);
        }
    }
}
