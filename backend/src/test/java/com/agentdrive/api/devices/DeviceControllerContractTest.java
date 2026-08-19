package com.agentdrive.api.devices;

import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.auth.AuthenticatedPrincipal;
import com.agentdrive.auth.CredentialAuthenticator;
import com.agentdrive.devices.DeviceStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceControllerContractTest {
    @Test
    void registrationPreservesSnakeCaseAndOwner() {
        UUID owner = UUID.randomUUID();
        StubDevices devices = new StubDevices(owner);
        WebTestClient client = client(owner, devices);

        client.post().uri("/api/v1/devices/register")
                .bodyValue(Map.of(
                        "device_id", "pixel-1",
                        "name", "Pixel",
                        "model", "Pixel 9",
                        "platform", "android",
                        "app_version", "1.2.3",
                        "sync", Map.of("enabled", true, "interval_hours", 6.0)
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.device_id").isEqualTo("pixel-1")
                .jsonPath("$.app_version").isEqualTo("1.2.3")
                .jsonPath("$.sync.enabled").isEqualTo(true);

        assertThat(devices.registeredUser).isEqualTo(owner);
        assertThat(devices.registered.get("model")).isEqualTo("Pixel 9");
    }

    @Test
    void listAndRemoveAreOwnerScoped() {
        UUID owner = UUID.randomUUID();
        StubDevices devices = new StubDevices(owner);
        WebTestClient client = client(owner, devices);

        client.get().uri("/api/v1/devices").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.devices[0].device_id").isEqualTo("pixel-1");

        client.delete().uri("/api/v1/devices/pixel-1").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.removed").isEqualTo("pixel-1");
        assertThat(devices.removed).isEqualTo("pixel-1");
    }

    private WebTestClient client(UUID owner, StubDevices devices) {
        CredentialAuthenticator authenticator = credential ->
                "session-token".equals(credential)
                        ? Optional.of(new AuthenticatedPrincipal(owner, AuthenticatedPrincipal.CredentialKind.SESSION))
                        : Optional.empty();
        return WebTestClient.bindToController(new DeviceController(
                        devices, new WebRequestPrincipalResolver(authenticator)))
                .controllerAdvice(new DeviceExceptionHandler())
                .build()
                .mutate().defaultCookie("agentdrive_session", "session-token").build();
    }

    private static final class StubDevices implements DeviceStore {
        private final UUID owner;
        private UUID registeredUser;
        private String removed;
        private Map<String, Object> registered = new LinkedHashMap<>();

        private StubDevices(UUID owner) {
            this.owner = owner;
        }

        @Override
        public List<Map<String, Object>> list(UUID userId) {
            assertThat(userId).isEqualTo(owner);
            return List.of(Map.of("device_id", "pixel-1", "name", "Pixel", "model", "Pixel 9",
                    "platform", "android", "app_version", "1.2.3", "sync", Map.of()));
        }

        @Override
        public Map<String, Object> register(UUID userId, String deviceId, String name, String model,
                                             String platform, String appVersion, Map<String, Object> sync) {
            registeredUser = userId;
            registered = new LinkedHashMap<>();
            registered.put("device_id", deviceId);
            registered.put("name", name);
            registered.put("model", model);
            registered.put("platform", platform);
            registered.put("app_version", appVersion);
            registered.put("sync", sync);
            return registered;
        }

        @Override
        public boolean remove(UUID userId, String deviceId) {
            assertThat(userId).isEqualTo(owner);
            removed = deviceId;
            return true;
        }
    }
}
