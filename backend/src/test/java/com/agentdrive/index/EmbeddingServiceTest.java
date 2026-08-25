package com.agentdrive.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingServiceTest {
    @Test
    void sendsRetrievalQueryTaskAndReturnsQueryVector() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/v1/embeddings", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"data\":[{\"index\":0,\"embedding\":[0.2,0.4,0.6]}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            UUID owner = UUID.randomUUID();
            EmbeddingRuntimeConfig configs = mock(EmbeddingRuntimeConfig.class);
            IndexStore index = mock(IndexStore.class);
            when(configs.find(owner)).thenReturn(Optional.of(new EmbeddingRuntimeConfig.Config(
                    "jina", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "jina-test", "test-key")));

            EmbeddingService.QueryEmbedding result = new EmbeddingService.Jina(
                    configs, index, new ObjectMapper()).embedQuery(owner, "付款和验收");

            assertThat(result.vector()).isEqualTo("[0.2,0.4,0.6]");
            assertThat(requestBody).hasValueSatisfying(body -> assertThat(body)
                    .contains("\"task\":\"retrieval.query\""));
            assertThat(requestBody).hasValueSatisfying(body -> assertThat(body)
                    .contains("付款和验收"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsOwnerChunksToJinaAndPersistsReturnedVector() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            byte[] body = "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            UUID owner = UUID.randomUUID();
            UUID chunk = UUID.randomUUID();
            EmbeddingRuntimeConfig configs = mock(EmbeddingRuntimeConfig.class);
            IndexStore index = mock(IndexStore.class);
            when(configs.find(owner)).thenReturn(Optional.of(new EmbeddingRuntimeConfig.Config(
                    "jina", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "jina-test", "test-key")));
            when(index.chunks(org.mockito.ArgumentMatchers.eq(owner), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(64)))
                    .thenReturn(List.of(Map.of("id", chunk.toString(), "content", "hello")), List.of());
            when(index.updateEmbedding(org.mockito.ArgumentMatchers.eq(owner), org.mockito.ArgumentMatchers.eq(chunk),
                    org.mockito.ArgumentMatchers.eq("[0.1,0.2,0.3]"), org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(1);

            Map<String, Object> result = new EmbeddingService.Jina(
                    configs, index, new ObjectMapper()).embed(owner, 64);

            assertThat(result).containsEntry("vectorized", true).containsEntry("embedded", 1);
            verify(index).updateEmbedding(owner, chunk, "[0.1,0.2,0.3]", result.get("fingerprint").toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void writesRemoteEmbeddingUsingTheChunkFileId() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            byte[] body = "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            UUID owner = UUID.randomUUID();
            UUID chunk = UUID.randomUUID();
            UUID file = UUID.randomUUID();
            EmbeddingRuntimeConfig configs = mock(EmbeddingRuntimeConfig.class);
            IndexStore index = mock(IndexStore.class);
            RemoteIndexDocumentClient remote = mock(RemoteIndexDocumentClient.class);
            ObjectProvider<RemoteIndexDocumentClient> remoteProvider = mock(ObjectProvider.class);
            when(remoteProvider.getIfAvailable()).thenReturn(remote);
            when(configs.find(owner)).thenReturn(Optional.of(new EmbeddingRuntimeConfig.Config(
                    "jina", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "jina-test", "test-key")));
            when(index.chunks(eq(owner), anyString(), eq(64)))
                    .thenReturn(List.of(Map.of("id", chunk.toString(), "file_id", file.toString(), "content", "hello",
                            "source_revision", 1L, "document_type", "text", "chunk_index", 0)), List.of());
            when(index.updateEmbedding(eq(owner), eq(chunk), eq("[0.1,0.2]"), anyString())).thenReturn(1);

            Map<String, Object> result = new EmbeddingService.Jina(configs, index, new ObjectMapper(), remoteProvider)
                    .embed(owner, 64);

            assertThat(result).containsEntry("vectorized", true).containsEntry("embedded", 1);
            verify(remote).updateEmbedding(eq(owner), eq(file), eq(1L), eq("text"), eq(0),
                    eq("[0.1,0.2]"), anyString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void continuesWithNewProviderBatchesUntilAllChunksAreEmbedded() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        ObjectMapper mapper = new ObjectMapper();
        server.createContext("/v1/embeddings", exchange -> {
            int count = mapper.readTree(exchange.getRequestBody()).path("input").size();
            requests.incrementAndGet();
            List<String> vectors = new ArrayList<>();
            for (int i = 0; i < count; i++) vectors.add("{\"index\":" + i + ",\"embedding\":[0.1]}");
            byte[] body = ("{\"data\":[" + String.join(",", vectors) + "]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            UUID owner = UUID.randomUUID();
            EmbeddingRuntimeConfig configs = mock(EmbeddingRuntimeConfig.class);
            IndexStore index = mock(IndexStore.class);
            when(configs.find(owner)).thenReturn(Optional.of(new EmbeddingRuntimeConfig.Config(
                    "jina", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "jina-test", "test-key")));
            List<Map<String, Object>> chunks = new ArrayList<>();
            for (int i = 0; i < 65; i++) {
                chunks.add(Map.of("id", UUID.randomUUID().toString(), "content", "chunk-" + i));
            }
            when(index.chunks(org.mockito.ArgumentMatchers.eq(owner), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(64)))
                    .thenReturn(chunks.subList(0, 64), chunks.subList(64, 65), List.of());
            when(index.updateEmbedding(org.mockito.ArgumentMatchers.eq(owner), org.mockito.ArgumentMatchers.any(UUID.class),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(1);

            Map<String, Object> result = new EmbeddingService.Jina(configs, index, mapper).embed(owner, 64);

            assertThat(result).containsEntry("vectorized", true).containsEntry("embedded", 65)
                    .containsEntry("batches", 2);
            assertThat(requests).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void forceFailureDoesNotClearPreviouslyPersistedVectors() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            UUID owner = UUID.randomUUID();
            UUID chunk = UUID.randomUUID();
            EmbeddingRuntimeConfig configs = mock(EmbeddingRuntimeConfig.class);
            IndexStore index = mock(IndexStore.class);
            when(configs.find(owner)).thenReturn(Optional.of(new EmbeddingRuntimeConfig.Config(
                    "jina", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "jina-test", "test-key")));
            when(index.chunks(eq(owner), anyString(), eq(List.of("notes.txt")), eq(true), eq(null), eq(64)))
                    .thenReturn(List.of(Map.of("id", chunk.toString(), "content", "old vector stays valid")));

            Map<String, Object> result = new EmbeddingService.Jina(configs, index, new ObjectMapper())
                    .embed(owner, List.of("notes.txt"), 64, true);

            assertThat(result).containsEntry("vectorized", false).containsEntry("reason", "provider_http_503");
            verify(index, never()).updateEmbedding(any(), any(), anyString(), anyString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void interruptedProviderRequestReturnsStableFailureAndPreservesInterruptStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        server.createContext("/v1/embeddings", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requestStarted.countDown();
            try {
                releaseResponse.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        AtomicReference<Map<String, Object>> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        Thread worker = null;
        try {
            UUID owner = UUID.randomUUID();
            UUID chunk = UUID.randomUUID();
            EmbeddingRuntimeConfig configs = mock(EmbeddingRuntimeConfig.class);
            IndexStore index = mock(IndexStore.class);
            when(configs.find(owner)).thenReturn(Optional.of(new EmbeddingRuntimeConfig.Config(
                    "jina", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "jina-test", "test-key")));
            when(index.chunks(eq(owner), anyString(), eq(64)))
                    .thenReturn(List.of(Map.of("id", chunk.toString(), "content", "wait for provider")));

            worker = new Thread(() -> {
                try {
                    result.set(new EmbeddingService.Jina(configs, index, new ObjectMapper()).embed(owner, 64));
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    interruptPreserved.set(Thread.currentThread().isInterrupted());
                }
            }, "embedding-interruption-test");
            worker.start();

            assertThat(requestStarted.await(5, TimeUnit.SECONDS)).isTrue();
            worker.interrupt();
            worker.join(TimeUnit.SECONDS.toMillis(5));

            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(result.get()).containsEntry("vectorized", false)
                    .containsEntry("reason", "embedding_interrupted");
            assertThat(interruptPreserved).isTrue();
            verify(index, never()).updateEmbedding(any(), any(), anyString(), anyString());
        } finally {
            releaseResponse.countDown();
            if (worker != null && worker.isAlive()) {
                worker.interrupt();
                worker.join(TimeUnit.SECONDS.toMillis(5));
            }
            server.stop(0);
        }
    }

    @Test
    void forceUsesChunkCursorSoUpdatedRowsAreNotSelectedAgain() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            byte[] body = "{\"data\":[{\"index\":0,\"embedding\":[0.5]}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            UUID owner = UUID.randomUUID();
            UUID chunk = UUID.randomUUID();
            EmbeddingRuntimeConfig configs = mock(EmbeddingRuntimeConfig.class);
            IndexStore index = mock(IndexStore.class);
            when(configs.find(owner)).thenReturn(Optional.of(new EmbeddingRuntimeConfig.Config(
                    "jina", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "jina-test", "test-key")));
            when(index.chunks(eq(owner), anyString(), eq(List.of("notes.txt")), eq(true), eq(null), eq(64)))
                    .thenReturn(List.of(Map.of("id", chunk.toString(), "content", "replace me")));
            when(index.chunks(eq(owner), anyString(), eq(List.of("notes.txt")), eq(true), eq(chunk), eq(64)))
                    .thenReturn(List.of());
            when(index.updateEmbedding(eq(owner), eq(chunk), eq("[0.5]"), anyString())).thenReturn(1);

            Map<String, Object> result = new EmbeddingService.Jina(configs, index, new ObjectMapper())
                    .embed(owner, List.of("notes.txt"), 64, true);

            assertThat(result).containsEntry("vectorized", true).containsEntry("embedded", 1);
            verify(index).chunks(eq(owner), anyString(), eq(List.of("notes.txt")), eq(true), eq(chunk), eq(64));
        } finally {
            server.stop(0);
        }
    }
}
