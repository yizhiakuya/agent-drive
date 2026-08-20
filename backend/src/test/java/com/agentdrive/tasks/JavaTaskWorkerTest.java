package com.agentdrive.tasks;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JavaTaskWorkerTest {
    @Test
    void isolatesScheduleAndOutboxFailuresFromTaskExecution() {
        ScheduleStore schedules = mock(ScheduleStore.class);
        IndexOutboxConsumer outbox = mock(IndexOutboxConsumer.class);
        IndexTaskHandler handler = mock(IndexTaskHandler.class);
        TaskWorkerStore workers = mock(TaskWorkerStore.class);
        doThrow(new IllegalStateException("schedule unavailable")).when(schedules).dispatchDueAll(20);
        doThrow(new IllegalStateException("outbox unavailable")).when(outbox).consumeOnce(20);
        JavaTaskWorker worker = new JavaTaskWorker(schedules, outbox, handler, workers);

        assertThatCode(worker::tick).doesNotThrowAnyException();

        verify(schedules).dispatchDueAll(20);
        verify(outbox).consumeOnce(20);
        verify(handler).runOnce(anyString());
        worker.close();
    }
}
