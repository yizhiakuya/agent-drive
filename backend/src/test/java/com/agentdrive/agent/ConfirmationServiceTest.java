package com.agentdrive.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmationServiceTest {
    @Test
    void signsExactArgumentsRedactsMessageAndRejectsReplay() {
        ConfirmationService service = new ConfirmationService(
                "test-signing-key".getBytes(StandardCharsets.UTF_8), new ObjectMapper()
        );
        Map<String, Object> arguments = Map.of(
                "action", "call",
                "operation", "INTERNAL delete_file",
                "api_key", "sk-secret"
        );

        Map<String, Object> pending = service.issue("backend_api", arguments);

        assertThat(pending).containsKeys("tool", "arguments", "nonce", "ts", "signature", "message");
        assertThat(pending.get("message")).asString().doesNotContain("sk-secret");
        assertThat(service.verifyAndConsume(pending, List.of(pending))).isTrue();
        assertThat(service.verifyAndConsume(pending, List.of(pending))).isFalse();
    }

    @Test
    void stateStoreAllowsASecondServiceInstanceToResumePending() {
        InMemoryConfirmationStateStore state = new InMemoryConfirmationStateStore();
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> arguments = Map.of("operation", "INTERNAL delete_file");
        ConfirmationService issuer = new ConfirmationService(
                "shared-key".getBytes(StandardCharsets.UTF_8), mapper, state
        );
        Map<String, Object> pending = issuer.issue("session-1", "backend_api", arguments);

        ConfirmationService resumed = new ConfirmationService(
                "shared-key".getBytes(StandardCharsets.UTF_8), mapper, state
        );

        assertThat(resumed.findIssued("session-1", "backend_api", arguments)).isNotNull();
        assertThat(resumed.verifyAndConsume("session-1", pending, List.of(pending))).isTrue();
        assertThat(resumed.findIssued("session-1", "backend_api", arguments)).isNull();
    }

    @Test
    void rejectsChangedArguments() {
        ConfirmationService service = ConfirmationService.random(new ObjectMapper());
        Map<String, Object> pending = service.issue("backend_api", Map.of("operation", "INTERNAL delete_file"));
        Map<String, Object> changed = new java.util.LinkedHashMap<>(pending);
        changed.put("arguments", Map.of("operation", "INTERNAL other_file"));

        assertThat(service.verifyAndConsume(pending, List.of(changed))).isFalse();
    }
}
