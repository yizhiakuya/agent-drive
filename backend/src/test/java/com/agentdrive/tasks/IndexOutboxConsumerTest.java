package com.agentdrive.tasks;

import com.agentdrive.outbox.OutboxStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Test
    void deadLettersMalformedPayloadWithoutPublishingOrEnqueuing() {
        OutboxStore outbox = mock(OutboxStore.class);
        TaskStore tasks = mock(TaskStore.class);
        UUID owner = UUID.randomUUID();
        when(outbox.pendingAll(20)).thenReturn(List.of(Map.of(
                "id", 43L,
                "user_id", owner.toString(),
                "event_type", "file.changed",
                "payload", Map.of(),
                "payload_error", "invalid_payload_json"
        )));
        when(outbox.recordFailure(43L, "invalid_payload_json", true)).thenReturn(true);
        IndexOutboxConsumer consumer = new IndexOutboxConsumer(outbox, tasks);

        assertThat(consumer.consumeOnce(20)).isZero();

        verify(outbox).recordFailure(43L, "invalid_payload_json", true);
        verify(outbox, never()).markPublished(owner, 43L);
        verifyNoInteractions(tasks);
    }

    @Test
    void deadLettersUnknownEventType() {
        OutboxStore outbox = mock(OutboxStore.class);
        TaskStore tasks = mock(TaskStore.class);
        UUID owner = UUID.randomUUID();
        when(outbox.pendingAll(20)).thenReturn(List.of(Map.of(
                "id", 44L,
                "user_id", owner.toString(),
                "event_type", "file.mystery",
                "payload", Map.of("action", "upsert", "paths", List.of("notes.txt"))
        )));
        IndexOutboxConsumer consumer = new IndexOutboxConsumer(outbox, tasks);

        assertThat(consumer.consumeOnce(20)).isZero();

        verify(outbox).recordFailure(44L, "unsupported_event_type", true);
        verify(outbox, never()).markPublished(owner, 44L);
        verifyNoInteractions(tasks);
    }

    @Test
    void skipsStructurallyImpossibleMalformedEventId() {
        OutboxStore outbox = mock(OutboxStore.class);
        TaskStore tasks = mock(TaskStore.class);
        when(outbox.pendingAll(20)).thenReturn(List.of(Map.of(
                "id", "not-a-number",
                "user_id", UUID.randomUUID().toString(),
                "event_type", "file.changed",
                "payload", Map.of("action", "upsert", "paths", List.of("notes.txt"))
        )));
        IndexOutboxConsumer consumer = new IndexOutboxConsumer(outbox, tasks);

        assertThat(consumer.consumeOnce(20)).isZero();

        verifyNoInteractions(tasks);
        verify(outbox, never()).markPublished(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(outbox, never()).recordFailure(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void recordsTransientEnqueueFailureAndLeavesEventPending() {
        OutboxStore outbox = mock(OutboxStore.class);
        TaskStore tasks = mock(TaskStore.class);
        UUID owner = UUID.randomUUID();
        when(outbox.pendingAll(20)).thenReturn(List.of(Map.of(
                "id", 45L,
                "user_id", owner.toString(),
                "event_type", "file.changed",
                "payload", Map.of("action", "upsert", "paths", List.of("notes.txt"))
        )));
        when(tasks.enqueue(owner, "index.file", "index", Map.of("path", "notes.txt"),
                "outbox-index:45", "outbox.file.changed", null))
                .thenThrow(new IllegalStateException("database unavailable"));
        IndexOutboxConsumer consumer = new IndexOutboxConsumer(outbox, tasks);

        assertThat(consumer.consumeOnce(20)).isZero();

        verify(outbox).recordFailure(45L, "enqueue_failed: IllegalStateException", false);
        verify(outbox, never()).markPublished(owner, 45L);
    }
}
