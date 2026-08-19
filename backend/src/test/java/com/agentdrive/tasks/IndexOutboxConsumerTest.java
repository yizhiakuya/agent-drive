package com.agentdrive.tasks;

import com.agentdrive.outbox.OutboxStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexOutboxConsumerTest {
    @Test
    void convertsFileChangeIntoOwnerScopedIndexTaskAndPublishesEvent() {
        OutboxStore outbox = mock(OutboxStore.class);
        TaskStore tasks = mock(TaskStore.class);
        UUID owner = UUID.randomUUID();
        when(outbox.pendingAll(20)).thenReturn(List.of(Map.of(
                "id", 42L,
                "user_id", owner.toString(),
                "event_type", "file.changed",
                "payload", Map.of("action", "upsert", "paths", List.of("notes.txt"))
        )));
        when(outbox.markPublished(owner, 42L)).thenReturn(true);
        IndexOutboxConsumer consumer = new IndexOutboxConsumer(outbox, tasks);

        assertThat(consumer.consumeOnce(20)).isEqualTo(1);

        verify(tasks).enqueue(owner, "index.file", "index", Map.of("path", "notes.txt"),
                "outbox-index:42", "outbox.file.changed", null);
        verify(outbox).markPublished(owner, 42L);
    }
}
