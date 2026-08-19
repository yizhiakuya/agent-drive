package com.agentdrive.infrastructure.persistence;

import com.agentdrive.devices.DeviceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "AGENT_DRIVE_JDBC_TEST_URL", matches = ".+")
class MybatisDeviceStoreIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeviceStore devices;

    @Test
    void registersListsAndRevokesOwnerDeviceMetadata() {
        String userId = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO users(id, username, password_hash) VALUES (?::uuid, ?, ?)",
                    userId, "device-integration-" + userId, "test-hash");

            Map<String, Object> registered = devices.register(
                    UUID.fromString(userId), "pixel-1", "Pixel", "Pixel 9", "android", "1.2.3",
                    Map.of("enabled", true, "interval_hours", 6.0)
            );
            assertThat(registered)
                    .containsEntry("device_id", "pixel-1")
                    .containsEntry("model", "Pixel 9")
                    .containsEntry("app_version", "1.2.3");
            assertThat(((Map<?, ?>) registered.get("sync")).get("enabled")).isEqualTo(true);

            List<Map<String, Object>> listed = devices.list(UUID.fromString(userId));
            assertThat(listed).hasSize(1);
            assertThat(listed.get(0)).containsEntry("device_id", "pixel-1");
            assertThat(devices.remove(UUID.fromString(userId), "pixel-1")).isTrue();
            assertThat(devices.list(UUID.fromString(userId))).isEmpty();
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?::uuid", userId);
        }
    }
}
