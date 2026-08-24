package com.agentdrive.vision;

import com.agentdrive.files.FileStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies uncached multi-image batching and independent result mapping. */
class VisionDescriptionServiceTest {
    @TempDir
    Path temp;

    @Test
    void sendsFourOrFewerImagesAsOneBatchWithoutReusingOldDescriptions() throws Exception {
        UUID owner = UUID.randomUUID();
        Path first = temp.resolve("first.png");
        Path second = temp.resolve("second.png");
        Files.write(first, new byte[]{1, 2, 3});
        Files.write(second, new byte[]{4, 5, 6});
        VisionRuntimeConfig.Config config = new VisionRuntimeConfig.Config(
                "openai_compat", "http://127.0.0.1:1/v1", "vision-model", "secret");
        VisionRuntimeConfig configs = user -> Optional.of(config);
        FileStorageService files = mock(FileStorageService.class);
        VisionModelClient client = mock(VisionModelClient.class);
        when(files.fileForRead(owner, "first.png")).thenReturn(first);
        when(files.fileForRead(owner, "second.png")).thenReturn(second);
        when(client.describeBatch(eq(config), anyList())).thenReturn(Map.of(
                "image-0", "一张收据的截图，包含商品和金额信息。",
                "image-1", "一个产品包装盒，正面有品牌和规格文字。"));

        Map<String, Object> result = new VisionDescriptionService(configs, files, client)
                .describeFiles(owner, List.of("first.png", "second.png"));

        assertThat(result).containsEntry("ok", true);
        assertThat(result.get("items")).asList().hasSize(2);
        List<?> items = (List<?>) result.get("items");
        assertThat(((Map<?, ?>) items.get(0)).get("path")).isEqualTo("first.png");
        assertThat(((Map<?, ?>) items.get(1)).get("path")).isEqualTo("second.png");
        verify(client).describeBatch(eq(config), anyList());
        verify(client, never()).describe(eq(config), org.mockito.ArgumentMatchers.any(),
                eq("image/png"), org.mockito.ArgumentMatchers.anyString());
    }
}
