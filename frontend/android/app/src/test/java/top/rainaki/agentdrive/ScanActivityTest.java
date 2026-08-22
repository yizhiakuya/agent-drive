package top.rainaki.agentdrive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** ScanActivity 的纯 JVM 输入边界回归测试。 */
public class ScanActivityTest {

    @Test
    public void validateServerAcceptsHttpsOriginAndNormalizesSlash() {
        assertEquals("https://example.com/agent",
                ScanActivity.validateServer("  https://example.com/agent///  "));
    }

    @Test
    public void validateServerRejectsInsecureOrAmbiguousAddresses() {
        assertNull(ScanActivity.validateServer("http://example.com"));
        assertNull(ScanActivity.validateServer("https://user:secret@example.com"));
        assertNull(ScanActivity.validateServer("https://example.com?redirect=1"));
        assertNull(ScanActivity.validateServer("https://localhost"));
        assertNull(ScanActivity.validateServer("https://127.0.0.1"));
        assertNull(ScanActivity.validateServer("https://[::1]"));
    }

    @Test
    public void readLimitedUsesBoundedBufferAndPreservesBytes() throws Exception {
        byte[] expected = "pair-response".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, ScanActivity.readLimited(
                new ByteArrayInputStream(expected), 128));
    }

    @Test(expected = IOException.class)
    public void readLimitedRejectsResponseOverLimit() throws Exception {
        ScanActivity.readLimited(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), 3);
    }
}
