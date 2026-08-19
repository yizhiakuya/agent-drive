package com.agentdrive.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
}
