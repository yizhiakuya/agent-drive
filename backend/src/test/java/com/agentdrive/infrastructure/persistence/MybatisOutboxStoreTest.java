package com.agentdrive.infrastructure.persistence;

import com.agentdrive.infrastructure.persistence.mapper.OutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MybatisOutboxStoreTest {
    @Test
    void exposesMalformedPayloadAsAnExplicitConsumerError() {
        OutboxMapper mapper = mock(OutboxMapper.class);
        UUID owner = UUID.randomUUID();
        when(mapper.pendingAll(20)).thenReturn(List.of(Map.of(
                "id", 9L,
                "user_id", owner.toString(),
                "event_type", "file.changed",
                "payload_json", "{broken"
        )));
        MybatisOutboxStore outbox = new MybatisOutboxStore(mapper, new ObjectMapper());

        assertThat(outbox.pendingAll(20)).singleElement().satisfies(event -> {
            assertThat(event).containsEntry("payload", Map.of());
            assertThat(event).containsEntry("payload_error", "invalid_payload_json");
        });
    }
}
