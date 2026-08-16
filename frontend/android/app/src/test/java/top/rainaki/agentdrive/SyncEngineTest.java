package top.rainaki.agentdrive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class SyncEngineTest {

    @Test
    public void syncIsSerializedWithinTheAppProcess() throws Exception {
        assertEquals(true, Modifier.isSynchronized(
                SyncEngine.class.getDeclaredMethod("sync", android.content.Context.class).getModifiers()));
    }

    @Test
    public void copyAndDigestRejectsUnreadableMedia() {
        assertThrows(IOException.class,
                () -> SyncEngine.copyAndDigest(null, new ByteArrayOutputStream()));
    }

    @Test
    public void copyAndDigestCopiesBytesAndComputesMd5() throws Exception {
        byte[] source = "photo-bytes".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String md5 = SyncEngine.copyAndDigest(new ByteArrayInputStream(source), output);
        assertArrayEquals(source, output.toByteArray());
        assertEquals("571c58e834fd876178aca15d610f4512", md5);
    }

    @Test
    public void copyAndDigestDoesNotTreatZeroLengthReadAsEof() throws Exception {
        byte[] source = "after-zero".getBytes(StandardCharsets.UTF_8);
        InputStream input = new ByteArrayInputStream(source) {
            private boolean returnedZero;

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (!returnedZero) {
                    returnedZero = true;
                    return 0;
                }
                return super.read(buffer, offset, length);
            }
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SyncEngine.copyAndDigest(input, output);
        assertArrayEquals(source, output.toByteArray());
    }

    @Test
    public void failedSecondBlocksLaterCheckpointAndKeepsContiguousWatermark() {
        SyncEngine.CheckpointTracker tracker = new SyncEngine.CheckpointTracker(99);
        tracker.begin(100, 0);
        tracker.success(1);
        tracker.failure();
        tracker.success(3); // 失败后的成功不能跨过失败 _ID
        tracker.begin(101, 0); // 自动结束 100 秒
        tracker.success(4);
        tracker.finishGroup(true);

        assertEquals(99, tracker.lastDrained());
        assertEquals(100, tracker.pendingSecond());
        assertEquals(1, tracker.pendingMaxId());
    }

    @Test
    public void persistedPendingClearsOnlyAfterRemainingSecondDrains() {
        SyncEngine.CheckpointTracker tracker = new SyncEngine.CheckpointTracker(99, 100, 5);
        tracker.begin(100, 5);
        tracker.success(6);
        tracker.finishGroup(true);

        assertEquals(100, tracker.lastDrained());
        assertEquals(-1, tracker.pendingSecond());
        assertEquals(0, tracker.pendingMaxId());
    }

    @Test
    public void persistedPendingSurvivesExceptionBeforeAnyRow() {
        SyncEngine.CheckpointTracker tracker = new SyncEngine.CheckpointTracker(99, 100, 5);
        tracker.failure();
        tracker.finishGroup(false);

        assertEquals(99, tracker.lastDrained());
        assertEquals(100, tracker.pendingSecond());
        assertEquals(5, tracker.pendingMaxId());
    }

    @Test
    public void emptyRetryResolvesPersistedPendingButNotNewFailure() {
        SyncEngine.CheckpointTracker persisted = new SyncEngine.CheckpointTracker(99, 100, 5);
        persisted.finishEmpty();
        assertEquals(100, persisted.lastDrained());
        assertEquals(-1, persisted.pendingSecond());

        SyncEngine.CheckpointTracker fresh = new SyncEngine.CheckpointTracker(99);
        fresh.begin(100, 0);
        fresh.success(1);
        fresh.failure();
        fresh.finishGroup(false);
        fresh.finishEmpty();
        assertEquals(99, fresh.lastDrained());
        assertEquals(100, fresh.pendingSecond());
    }

    @Test
    public void truncationUsesSentinelSecondWithoutUploadingIt() {
        SyncEngine.CheckpointTracker tracker = new SyncEngine.CheckpointTracker(99);
        tracker.begin(100, 0);
        tracker.success(10);
        tracker.truncatedAt(101);

        assertEquals(100, tracker.lastDrained());
        assertEquals(101, tracker.pendingSecond());
        assertEquals(0, tracker.pendingMaxId());
    }

    @Test
    public void sameSecondTruncationResumesAfterLastContiguousId() {
        SyncEngine.CheckpointTracker tracker = new SyncEngine.CheckpointTracker(99);
        tracker.begin(100, 5);
        tracker.success(6);
        tracker.truncatedAt(100);

        assertEquals(99, tracker.lastDrained());
        assertEquals(100, tracker.pendingSecond());
        assertEquals(6, tracker.pendingMaxId());
    }

    @Test
    public void permanentSkipAdvancesWatermarkWithoutBlockingCheckpoint() {
        SyncEngine.CheckpointTracker tracker = new SyncEngine.CheckpointTracker(99);
        tracker.begin(100, 0);
        tracker.skip(5);
        tracker.success(7);
        tracker.finishGroup(true);

        assertEquals(100, tracker.lastDrained());
        assertEquals(-1, tracker.pendingSecond());
        assertEquals(0, tracker.pendingMaxId());
    }

    @Test
    public void permanentSkipAfterRealFailureDoesNotUnblockTheSecond() {
        SyncEngine.CheckpointTracker tracker = new SyncEngine.CheckpointTracker(99);
        tracker.begin(100, 0);
        tracker.failure();
        tracker.skip(3);
        tracker.finishGroup(true);

        assertEquals(99, tracker.lastDrained());
        assertEquals(100, tracker.pendingSecond());
        assertEquals(0, tracker.pendingMaxId());
    }

    @Test
    public void permanentClientErrorsAreClassifiedApartFromRetryableOnes() {
        assertTrue(SyncEngine.isPermanentClientError(400));
        assertTrue(SyncEngine.isPermanentClientError(413));
        assertTrue(SyncEngine.isPermanentClientError(415));
        assertTrue(SyncEngine.isPermanentClientError(416));
        assertTrue(SyncEngine.isPermanentClientError(422));
        assertFalse(SyncEngine.isPermanentClientError(404));
        assertFalse(SyncEngine.isPermanentClientError(405));
        assertFalse(SyncEngine.isPermanentClientError(408));
        assertFalse(SyncEngine.isPermanentClientError(409));
        assertFalse(SyncEngine.isPermanentClientError(429));
        assertFalse(SyncEngine.isPermanentClientError(401));
        assertFalse(SyncEngine.isPermanentClientError(500));
    }
}
