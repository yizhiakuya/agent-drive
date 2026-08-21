package top.rainaki.agentdrive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
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
        assertThrows(FileNotFoundException.class,
                () -> SyncEngine.copyAndDigest(null, new ByteArrayOutputStream()));
    }

    @Test
    public void classifyLocalMediaFailureMapsPermanentRetryAndInterruptBranches() {
        assertEquals(SyncEngine.LocalMediaResult.SKIP,
                SyncEngine.classifyLocalMediaFailure(new FileNotFoundException("deleted"), false));
        assertEquals(SyncEngine.LocalMediaResult.SKIP,
                SyncEngine.classifyLocalMediaFailure(new SecurityException("permission"), false));
        assertEquals(SyncEngine.LocalMediaResult.SKIP,
                SyncEngine.classifyLocalMediaFailure(
                        new IOException("wrapped", new FileNotFoundException("deleted")), false));

        assertEquals(SyncEngine.LocalMediaResult.ABORT,
                SyncEngine.classifyLocalMediaFailure(new InterruptedException("cancelled"), false));
        assertEquals(SyncEngine.LocalMediaResult.ABORT,
                SyncEngine.classifyLocalMediaFailure(new IOException("other"), true));

        assertEquals(SyncEngine.LocalMediaResult.RETRY,
                SyncEngine.classifyLocalMediaFailure(new IOException("temporary"), false));
        assertEquals(SyncEngine.LocalMediaResult.RETRY,
                SyncEngine.classifyLocalMediaFailure(new IllegalStateException("temporary"), false));
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
    public void finishGroupWithoutOpenSecondIsANoOp() {
        SyncEngine.CheckpointTracker tracker = new SyncEngine.CheckpointTracker(99);
        tracker.finishGroup(true); // 无 begin 的组：不得改动检查点或产生 pending

        assertEquals(99, tracker.lastDrained());
        assertEquals(-1, tracker.pendingSecond());
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

    // ---------------------------------------------------------------------
    // classifySyncStatus：上传路径（upload=true）的全分支覆盖
    // ---------------------------------------------------------------------

    @Test
    public void classifySyncStatusUploadMapsAllBranches() {
        // 2xx（含 200/201/204）→ 成功；404 → 可重试（上传路径不算未命中）
        assertEquals(SyncEngine.SyncResult.SUCCESS, SyncEngine.classifySyncStatus(200, true));
        assertEquals(SyncEngine.SyncResult.SUCCESS, SyncEngine.classifySyncStatus(201, true));
        assertEquals(SyncEngine.SyncResult.SUCCESS, SyncEngine.classifySyncStatus(204, true));
        assertEquals(SyncEngine.SyncResult.RETRY, SyncEngine.classifySyncStatus(404, true));

        // 连接级失败：401/403/≥500 → 中止
        assertEquals(SyncEngine.SyncResult.ABORT, SyncEngine.classifySyncStatus(401, true));
        assertEquals(SyncEngine.SyncResult.ABORT, SyncEngine.classifySyncStatus(403, true));
        assertEquals(SyncEngine.SyncResult.ABORT, SyncEngine.classifySyncStatus(500, true));
        assertEquals(SyncEngine.SyncResult.ABORT, SyncEngine.classifySyncStatus(503, true));
        assertEquals(SyncEngine.SyncResult.ABORT, SyncEngine.classifySyncStatus(599, true));

        // 永久 4xx → 跳过
        assertEquals(SyncEngine.SyncResult.SKIP, SyncEngine.classifySyncStatus(400, true));
        assertEquals(SyncEngine.SyncResult.SKIP, SyncEngine.classifySyncStatus(413, true));
        assertEquals(SyncEngine.SyncResult.SKIP, SyncEngine.classifySyncStatus(415, true));
        assertEquals(SyncEngine.SyncResult.SKIP, SyncEngine.classifySyncStatus(416, true));
        assertEquals(SyncEngine.SyncResult.SKIP, SyncEngine.classifySyncStatus(422, true));

        // 其余 4xx → 可重试
        assertEquals(SyncEngine.SyncResult.RETRY, SyncEngine.classifySyncStatus(405, true));
        assertEquals(SyncEngine.SyncResult.RETRY, SyncEngine.classifySyncStatus(408, true));
        assertEquals(SyncEngine.SyncResult.RETRY, SyncEngine.classifySyncStatus(409, true));
        assertEquals(SyncEngine.SyncResult.RETRY, SyncEngine.classifySyncStatus(429, true));

        // 3xx 等非 4xx 非 2xx → 可重试（冻结水位）
        assertEquals(SyncEngine.SyncResult.RETRY, SyncEngine.classifySyncStatus(301, true));
    }

    // ---------------------------------------------------------------------
    // classifySyncStatus：预检路径（upload=false）语义差异
    // ---------------------------------------------------------------------

    @Test
    public void classifySyncStatusDedupeMapsAllBranches() {
        // 仅 200 视为命中；404 视为未命中（继续上传）；其余 2xx 仍按可重试
        assertEquals(SyncEngine.SyncResult.SUCCESS, SyncEngine.classifySyncStatus(200, false));
        assertEquals(SyncEngine.SyncResult.MISS, SyncEngine.classifySyncStatus(404, false));
        assertEquals(SyncEngine.SyncResult.RETRY, SyncEngine.classifySyncStatus(201, false));

        // 连接级失败：401/403/≥500 → 中止
        assertEquals(SyncEngine.SyncResult.ABORT, SyncEngine.classifySyncStatus(401, false));
        assertEquals(SyncEngine.SyncResult.ABORT, SyncEngine.classifySyncStatus(403, false));
        assertEquals(SyncEngine.SyncResult.ABORT, SyncEngine.classifySyncStatus(500, false));

        // 永久 4xx → 跳过
        assertEquals(SyncEngine.SyncResult.SKIP, SyncEngine.classifySyncStatus(400, false));
        assertEquals(SyncEngine.SyncResult.SKIP, SyncEngine.classifySyncStatus(413, false));
        assertEquals(SyncEngine.SyncResult.SKIP, SyncEngine.classifySyncStatus(415, false));
        assertEquals(SyncEngine.SyncResult.SKIP, SyncEngine.classifySyncStatus(416, false));
        assertEquals(SyncEngine.SyncResult.SKIP, SyncEngine.classifySyncStatus(422, false));

        // 其余 4xx → 可重试
        assertEquals(SyncEngine.SyncResult.RETRY, SyncEngine.classifySyncStatus(405, false));
        assertEquals(SyncEngine.SyncResult.RETRY, SyncEngine.classifySyncStatus(408, false));
        assertEquals(SyncEngine.SyncResult.RETRY, SyncEngine.classifySyncStatus(409, false));
        assertEquals(SyncEngine.SyncResult.RETRY, SyncEngine.classifySyncStatus(429, false));
    }

    // ---------------------------------------------------------------------
    // buildResumeSelection：同秒续传选择串
    // ---------------------------------------------------------------------

    @Test
    public void buildResumeSelectionContainsDateTimeWatermarkAndIdContinuation() {
        String selection = SyncEngine.buildResumeSelection();

        // 前段：date_added > ?（先扫 pending 秒之后的更晚秒）
        assertTrue(selection.contains("date_added > ?"));
        // 后段：OR (date_added = ? AND _id > ?)（同秒 _ID 水位续传）
        assertTrue(selection.contains("OR (date_added = ? AND _id > ?)"));

        // 完整串与 MediaStore 常量逐段一致（常量编译期内联，JVM 可安全比较）
        String expected = android.provider.MediaStore.Images.Media.DATE_ADDED + " > ? OR ("
                + android.provider.MediaStore.Images.Media.DATE_ADDED + " = ? AND "
                + android.provider.MediaStore.Images.Media._ID + " > ?)";
        assertEquals(expected, selection);
    }

    // ---------------------------------------------------------------------
    // CheckpointTracker：finishGroup 未完成秒 / 跨秒 Math.max 推进 / 续传水位
    // ---------------------------------------------------------------------

    @Test
    public void incompleteGroupFreezesSecondAsPendingAndNeverAdvancesCheckpoint() {
        // 一组未完整排空（complete=false）：该秒挂 pending，检查点不得推进。
        SyncEngine.CheckpointTracker tracker = new SyncEngine.CheckpointTracker(99);
        tracker.begin(100, 0);
        tracker.success(3);
        tracker.finishGroup(false);

        assertEquals(99, tracker.lastDrained());
        assertEquals(100, tracker.pendingSecond());
        assertEquals(3, tracker.pendingMaxId());
    }

    @Test
    public void failedGroupThenCompletedNextGroupDoesNotCrossFailedSecond() {
        // 失败秒 100 之后，更晚秒 101 全部成功也不能越过 100。
        SyncEngine.CheckpointTracker tracker = new SyncEngine.CheckpointTracker(99);
        tracker.begin(100, 0);
        tracker.failure();
        tracker.begin(101, 0); // 自动结束 100 秒（complete=true 但仍悬挂 pending）
        tracker.success(7);
        tracker.finishGroup(true);

        assertEquals(99, tracker.lastDrained());
        assertEquals(100, tracker.pendingSecond());
        assertEquals(0, tracker.pendingMaxId());
    }

    @Test
    public void crossSecondAdvanceUsesMathMaxAgainstLastDrained() {
        // lastDrained 已比当前秒更大时，完成组不得回退检查点（Math.max 语义）。
        SyncEngine.CheckpointTracker tracker = new SyncEngine.CheckpointTracker(120);
        tracker.begin(100, 0);
        tracker.success(1);
        tracker.finishGroup(true);

        assertEquals(120, tracker.lastDrained());
        assertEquals(-1, tracker.pendingSecond());
    }

    @Test
    public void advancingToNewSecondCompletesPreviousWholeSecond() {
        // 上一整秒全部成功 → 检查点推进到该秒；新秒随后独立推进。
        SyncEngine.CheckpointTracker tracker = new SyncEngine.CheckpointTracker(99);
        tracker.begin(100, 0);
        tracker.success(4);
        tracker.begin(101, 0);
        tracker.success(5);
        tracker.finishGroup(true);

        assertEquals(101, tracker.lastDrained());
        assertEquals(-1, tracker.pendingSecond());
        assertEquals(0, tracker.pendingMaxId());
    }

    @Test
    public void pendingResumeSeedKeepsMaximumOfPersistedAndCurrentWatermark() {
        // 续传同秒：begin 的 initialMaxId 与持久化 pendingMaxId 取较大者为水位起点。
        SyncEngine.CheckpointTracker tracker = new SyncEngine.CheckpointTracker(99, 100, 5);
        tracker.begin(100, 2);
        tracker.success(6);
        tracker.finishGroup(true);

        assertEquals(100, tracker.lastDrained());
        assertEquals(-1, tracker.pendingSecond());
        assertEquals(0, tracker.pendingMaxId());
    }

    @Test
    public void pendingSecondLowerThanNewSecondGetsResolvedWhenCrossed() {
        // 查询结果已越过 pending 秒且该秒无剩余行 → 解析 pending 并推进 lastDrained。
        SyncEngine.CheckpointTracker tracker = new SyncEngine.CheckpointTracker(99, 100, 5);
        tracker.begin(101, 0);
        tracker.success(1);
        tracker.finishGroup(true);

        assertEquals(101, tracker.lastDrained());
        assertEquals(-1, tracker.pendingSecond());
    }

    @Test
    public void rememberPendingKeepsEarliestPendingSecondAcrossMismatchedSeconds() {
        // pending 已记为某秒，后续不同秒的 pending 不覆盖它（只记录最早失败秒）。
        SyncEngine.CheckpointTracker tracker = new SyncEngine.CheckpointTracker(99);
        tracker.begin(100, 0);
        tracker.failure();
        tracker.finishGroup(false); // pending=100, maxId=0
        tracker.begin(101, 0);
        tracker.failure();
        tracker.finishGroup(false); // 不得把 pending 覆盖成 101

        assertEquals(99, tracker.lastDrained());
        assertEquals(100, tracker.pendingSecond());
        assertEquals(0, tracker.pendingMaxId());
    }

    @Test
    public void rememberPendingSameSecondKeepsMaximumWatermark() {
        // 同一秒重复挂 pending 时，水位取 Math.max，不丢失已成功推进的 _ID。
        SyncEngine.CheckpointTracker tracker = new SyncEngine.CheckpointTracker(99);
        tracker.begin(100, 0);
        tracker.success(8);
        tracker.failure();
        tracker.success(12); // 失败后 watermarkOpen=false，不再推进
        tracker.finishGroup(false);

        assertEquals(99, tracker.lastDrained());
        assertEquals(100, tracker.pendingSecond());
        assertEquals(8, tracker.pendingMaxId());
    }

    // ---------------------------------------------------------------------
    // writePart：文件名清洗 + multipart 组装（反射直达 private static 纯组装逻辑）
    // ---------------------------------------------------------------------

    private static Method writePart() throws NoSuchMethodException {
        Method m = SyncEngine.class.getDeclaredMethod("writePart",
                OutputStream.class, String.class, String.class, String.class, String.class, InputStream.class);
        m.setAccessible(true);
        return m;
    }

    private static String invokeWritePart(String boundary, String field, String valueOrName,
                                          String mime, InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writePart().invoke(null, out, boundary, field, valueOrName, mime, in);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void writePartSanitizesFilenameBackslashesSlashesQuotesAndNewlines() throws Exception {
        String body = invokeWritePart("B", "file", "a\\b/c\r\nd\"e.png", "image/png",
                new ByteArrayInputStream(new byte[0]));

        assertEquals("--B\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"a_b_c__d_e.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n\r\n", body);
    }

    @Test
    public void writePartStripsNewlinesFromMimeButKeepsFilenameFieldWrapper() throws Exception {
        String body = invokeWritePart("B", "file", "plain.png", "text/plain\r\n",
                new ByteArrayInputStream(new byte[0]));

        // 清洗后的 mime="text/plain"；若原始 \r\n 未被剥除，会多出一组空白行（连续 4 个 CRLF）
        assertTrue(body.contains("filename=\"plain.png\"\r\nContent-Type: text/plain\r\n"));
        assertFalse(body.contains("text/plain\r\n\r\n\r\n\r\n"));
        assertTrue(body.endsWith("\r\n\r\n\r\n")); // 头部空行(\r\n\r\n) + file 空正文后的 \r\n 尾
    }

    @Test
    public void writePartPlainFieldEmitsNameValuePairWithoutFilename() throws Exception {
        String body = invokeWritePart("XYZ", "md5", "d41d8cd98f00b204e9800998ecf8427e", null, null);

        assertEquals("--XYZ\r\n"
                + "Content-Disposition: form-data; name=\"md5\"\r\n\r\n"
                + "d41d8cd98f00b204e9800998ecf8427e\r\n", body);
    }

    @Test
    public void writePartCopiesFileContentVerbatimThenTerminatingCrlf() throws Exception {
        byte[] content = "file-bytes".getBytes(StandardCharsets.UTF_8);
        String body = invokeWritePart("B", "file", "x.bin", "application/octet-stream",
                new ByteArrayInputStream(content));

        assertTrue(body.startsWith("--B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"x.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n"));
        assertTrue(body.endsWith("file-bytes\r\n"));
        assertEquals("file-bytes", body.substring(body.indexOf("\r\n\r\n") + 4, body.lastIndexOf("\r\n")));
    }
}
