package com.agentdrive.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentdrive.net.HttpClientSupport;
import com.agentdrive.progress.TaskProgressReporter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 把索引文本块发送给 embedding provider 并把向量写回索引的服务接口。
 * 调用以用户为边界，可选择全部待处理文件或指定路径；实现必须用当前 provider 配置指纹筛选 chunks，
 * 并把外部请求放在任务 Worker 中执行，而不是阻塞 HTTP/Agent 请求。
 */
public interface EmbeddingService {
    /**
     * 表示一次查询 embedding 的结果；向量只在服务端传给 pgvector，不返回给客户端。
     *
     * @param vector PostgreSQL/pgvector 数组文字
     * @param fingerprint 生成该向量的 embedding 配置指纹
     */
    record QueryEmbedding(String vector, String fingerprint) {
    }

    /**
     * 向量化该用户所有仍缺少当前配置向量的 chunks。
     * 这是带默认参数的便捷入口：不限制文件路径，也不清除已有向量。
     *
     * @param userId 文件归属用户的 UUID。
     * @param limit 本次每批最多从索引取出的 chunk 数量。
     * @return 向量化结果，包含成功标识、写回数量和失败原因（如有）。
     */
    default Map<String, Object> embed(UUID userId, int limit) {
        return embed(userId, List.of(), limit, false);
    }

    /**
     * 向量化指定文件路径中仍缺少当前配置向量的 chunks，不强制重算已有向量。
     *
     * @param userId 文件归属用户的 UUID。
     * @param paths 要处理的用户相对文件路径列表；空列表表示不做路径过滤。
     * @param limit 本次每批最多从索引取出的 chunk 数量。
     * @return 向量化结果及处理统计。
     */
    default Map<String, Object> embed(UUID userId, List<String> paths, int limit) {
        return embed(userId, paths, limit, false);
    }

    /**
     * 按批次调用 provider，为指定范围的 chunks 生成向量并持久化。
     * {@code force} 为真时先清除指定范围的已有向量；未配置 provider 时返回未配置结果，网络、响应数量或向量格式错误时返回可重试的失败统计。
     *
     * @param userId 文件归属用户的 UUID。
     * @param paths 要处理的用户相对文件路径列表；空列表表示全部文件。
     * @param limit 每轮从索引选取的上限，实际批次还受 provider 的 64 条上限约束。
     * @param force 是否先清除目标范围的旧向量。
     * @return 含 {@code vectorized}、{@code embedded}、{@code batches} 和失败原因的结果 map。
     */
    Map<String, Object> embed(UUID userId, List<String> paths, int limit, boolean force);

    /**
     * 执行向量化并报告批次进度；默认实现保持外部实现与旧调用方兼容。
     *
     * @param userId embedding 配置所属用户的 UUID。
     * @param paths 要处理的用户相对文件路径列表；空列表表示全部文件。
     * @param limit 每批最多处理的 chunk 数。
     * @param force 是否先清除目标范围的旧向量。
     * @param progress 当前任务进度回调，可为空。
     * @return 向量化结果及处理统计。
     */
    default Map<String, Object> embed(UUID userId, List<String> paths, int limit, boolean force,
                                      TaskProgressReporter progress) {
        return embed(userId, paths, limit, force);
    }

    /**
     * 为语义搜索问题生成查询向量。
     * Provider 请求必须使用 {@code retrieval.query} 任务类型，并返回与文档 embedding
     * 相同模型配置的 fingerprint，避免跨模型混合检索。
     *
     * @param userId embedding 配置所属用户的 UUID
     * @param query 用户输入的自然语言搜索问题
     * @return 查询向量和当前配置指纹
     * @throws IllegalStateException 未配置 provider 或 provider 请求失败时抛出
     */
    QueryEmbedding embedQuery(UUID userId, String query);

    /**
     * 使用 Jina OpenAI 兼容 embeddings API 的实现。
     * 每次请求最多发送 64 个文本块，严格校验响应中的 data 数量和有限数值向量，再通过 {@link IndexStore} 按 chunk 写回；
     * JDK 客户端使用服务配置的 HTTP(S) 代理且禁止自动跟随重定向。
     */
    @Service
    @Profile({"java-files", "java-auth", "java-chat"})
    final class Jina implements EmbeddingService {
        private static final Duration TIMEOUT = Duration.ofSeconds(30);
        private static final int MAX_BATCH_SIZE = 64;
        private final EmbeddingRuntimeConfig configs;
        private final IndexStore index;
        private final ObjectMapper objectMapper;
        private final HttpClient client = HttpClientSupport.builder(TIMEOUT).build();

        /**
         * 创建 Jina embedding 实现，并保存配置、索引和 JSON 编解码依赖。
         * HTTP 客户端在字段初始化时创建，后续调用共享该客户端以复用连接。
         *
         * @param configs 按用户读取 provider 地址、模型和 API 密钥的配置存储。
         * @param index 查询待处理 chunks 并持久化向量的索引存储。
         * @param objectMapper 构造请求 JSON 和解析 provider 响应的 Jackson mapper。
         */
        public Jina(EmbeddingRuntimeConfig configs, IndexStore index, ObjectMapper objectMapper) {
            this.configs = configs;
            this.index = index;
            this.objectMapper = objectMapper;
        }

        /**
         * 执行完整的按用户向量化循环。
         * 每轮从索引取当前 fingerprint 下的待处理 chunks，直到没有剩余；单批失败立即返回已处理统计，线程中断会恢复中断标志并返回中断原因。
         *
         * @param userId 文件归属用户的 UUID。
         * @param paths 要筛选的用户相对路径列表。
         * @param limit 每轮查询上限，最终会限制在 1 到 64 之间。
         * @param force 是否先清除所选文件的旧向量。
         * @return provider 调用和索引写回的累计结果。
         */
        @Override
        public Map<String, Object> embed(UUID userId, List<String> paths, int limit, boolean force) {
            return embed(userId, paths, limit, force, TaskProgressReporter.noop());
        }

        /**
         * 执行向量化循环并在每个 provider 批次前后报告阶段信息。
         * 总 chunk 数由索引查询动态产生，因此处理期间使用不定总量，完成时再落为确定计数。
         *
         * @param userId 文件归属用户的 UUID。
         * @param paths 要筛选的用户相对路径列表。
         * @param limit 每轮查询上限，最终会限制在 1 到 64 之间。
         * @param force 是否清除并重算所选文件已有向量。
         * @param progress 当前任务进度回调，可为空。
         * @return provider 调用和索引写回的累计结果。
         */
        @Override
        public Map<String, Object> embed(UUID userId, List<String> paths, int limit, boolean force,
                                         TaskProgressReporter progress) {
            TaskProgressReporter reporter = progress == null ? TaskProgressReporter.noop() : progress;
            Optional<EmbeddingRuntimeConfig.Config> configured = configs.find(userId);
            if (configured.isEmpty()) {
                reporter.report(0, 0, "向量化未执行：未配置向量服务");
                return Map.of("vectorized", false, "reason", "embedding_not_configured");
            }
            EmbeddingRuntimeConfig.Config config = configured.get();
            String apiKey = config.apiKey();
            if (apiKey.isBlank()) {
                reporter.report(0, 0, "向量化未执行：未配置向量服务");
                return Map.of("vectorized", false, "reason", "embedding_not_configured");
            }
            String fingerprint = fingerprint(config);
            List<String> selectedPaths = paths == null ? List.of() : List.copyOf(paths);
            if (force) index.clearEmbeddings(userId, selectedPaths);
            String selectionFingerprint = fingerprint;
            int batchSize = Math.max(1, Math.min(limit, MAX_BATCH_SIZE));
            int embedded = 0;
            int batches = 0;
            reporter.report(0, 0, selectedPaths.isEmpty()
                    ? "向量化：准备处理全部文本块"
                    : "向量化：准备处理 " + selectedPaths.size() + " 个文件");
            try {
                while (true) {
                    List<Map<String, Object>> chunks = selectedPaths.isEmpty()
                            ? index.chunks(userId, selectionFingerprint, batchSize)
                            : index.chunks(userId, selectionFingerprint, selectedPaths, batchSize);
                    if (chunks.isEmpty()) {
                        reporter.report(embedded, embedded, embedded == 0
                                ? "向量化：没有待处理文本块"
                                : "向量化：已完成，共写入 " + embedded + " 个文本块");
                        return Map.of(
                                "vectorized", true,
                                "embedded", embedded,
                                "batches", batches,
                                "selected_files", selectedPaths.size(),
                                "fingerprint", fingerprint
                            );
                    }
                    reporter.report(embedded, 0, "向量化：正在处理第 " + (batches + 1)
                            + " 批（" + chunks.size() + " 个文本块）");
                    Map<String, Object> batch = embedBatch(userId, config, apiKey, chunks, fingerprint);
                    if (!Boolean.TRUE.equals(batch.get("vectorized"))) {
                        reporter.report(embedded, 0, "向量化失败：" + batch.get("reason"));
                        Map<String, Object> result = new LinkedHashMap<>(batch);
                        result.put("embedded", embedded + number(batch.get("embedded")));
                        result.put("batches", batches);
                        result.put("selected_files", selectedPaths.size());
                        return result;
                    }
                    int batchEmbedded = number(batch.get("embedded"));
                    if (batchEmbedded != chunks.size()) {
                        return failure("embedding_persist_mismatch", embedded + batchEmbedded,
                                batches + 1, selectedPaths.size(), fingerprint);
                    }
                    embedded += batchEmbedded;
                    batches++;
                    reporter.report(embedded, 0, "向量化：第 " + batches + " 批完成，已写入 "
                            + embedded + " 个文本块");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                reporter.report(embedded, 0, "向量化已中断");
                return failure("embedding_interrupted", embedded, batches, selectedPaths.size(), fingerprint);
            } catch (Exception error) {
                reporter.report(embedded, 0, "向量化失败：" + safeMessage(error));
                Map<String, Object> result = failure("embedding_failed", embedded, batches,
                        selectedPaths.size(), fingerprint);
                result.put("error", safeMessage(error));
                return result;
            }
        }

        /**
         * 调用 Jina 生成一个 retrieval.query 向量供 pgvector 检索。
         * 该方法只读取 owner 的运行时配置，不写入 chunk，避免搜索请求改变索引状态。
         *
         * @param userId embedding 配置所属用户的 UUID
         * @param query 已清理的自然语言搜索问题
         * @return 查询向量和对应配置指纹
         * @throws IllegalStateException 配置缺失、provider 返回错误或响应向量无效时抛出
         */
        @Override
        public QueryEmbedding embedQuery(UUID userId, String query) {
            Optional<EmbeddingRuntimeConfig.Config> configured = configs.find(userId);
            if (configured.isEmpty() || configured.get().apiKey().isBlank()) {
                throw new IllegalStateException("embedding_not_configured");
            }
            EmbeddingRuntimeConfig.Config config = configured.get();
            try {
                String requestBody = objectMapper.writeValueAsString(Map.of(
                        "model", config.model(),
                        "task", "retrieval.query",
                        "input", List.of(query)
                ));
                HttpRequest request = HttpRequest.newBuilder(endpoint(config.baseUrl()))
                        .timeout(TIMEOUT)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + config.apiKey())
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("embedding_query_provider_http_" + response.statusCode());
                }
                JsonNode data = objectMapper.readTree(response.body()).path("data");
                if (!data.isArray() || data.size() != 1) {
                    throw new IllegalStateException("embedding_query_response_mismatch");
                }
                String vector = vector(data.get(0).path("embedding"));
                if (vector.isBlank()) throw new IllegalStateException("embedding_query_response_invalid");
                return new QueryEmbedding(vector, fingerprint(config));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("embedding_query_interrupted", interrupted);
            } catch (IOException error) {
                throw new IllegalStateException("embedding_query_failed", error);
            }
        }

        /**
         * 将一批 chunk 发送到 Jina，并把对应向量逐条写回索引。
         * 非 2xx 响应、data 数量不匹配或向量含非有限数值时不写入错误结果；成功响应中的每个 chunk 必须成功更新，
         * 上层会据此检测持久化数量是否与请求数量一致。
         *
         * @param userId 文件归属用户的 UUID。
         * @param config 当前用户的 provider 地址和模型配置。
         * @param apiKey 当前用户的 Jina API 密钥，仅用于 Authorization header。
         * @param chunks 待发送的 chunk 记录，必须包含 content 和 id。
         * @param fingerprint 当前配置指纹，写回时与向量一起保存。
         * @return 本批是否成功及实际写回数量，provider 错误会以失败结果返回。
         * @throws IOException JSON 序列化或 HTTP 响应读取失败时抛出。
         * @throws InterruptedException HTTP 请求被线程中断时抛出。
         */
        private Map<String, Object> embedBatch(UUID userId, EmbeddingRuntimeConfig.Config config, String apiKey,
                                               List<Map<String, Object>> chunks, String fingerprint)
                throws IOException, InterruptedException {
            URI endpoint = endpoint(config.baseUrl());
            List<String> inputs = chunks.stream().map(chunk -> String.valueOf(chunk.get("content"))).toList();
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", config.model(),
                    "task", "retrieval.passage",
                    "input", inputs
            ));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Map.of("vectorized", false, "reason", "provider_http_" + response.statusCode(), "embedded", 0);
            }
            JsonNode data = objectMapper.readTree(response.body()).path("data");
            if (!data.isArray() || data.size() != chunks.size()) {
                return Map.of("vectorized", false, "reason", "embedding_response_mismatch", "embedded", 0);
            }
            int embedded = 0;
            for (int i = 0; i < chunks.size(); i++) {
                String vector = vector(data.get(i).path("embedding"));
                if (vector.isBlank()) {
                    return Map.of("vectorized", false, "reason", "embedding_response_invalid", "embedded", embedded);
                }
                UUID chunkId = UUID.fromString(String.valueOf(chunks.get(i).get("id")));
                int updated = index.updateEmbedding(userId, chunkId, vector, fingerprint);
                embedded += updated;
            }
            return Map.of("vectorized", true, "embedded", embedded);
        }

        /**
         * 统一创建一次向量化失败的统计结果。
         * 该方法只组装返回 map，不修改索引或触发重试。
         *
         * @param reason 机器可读失败原因。
         * @param embedded 已成功写回的 chunk 数。
         * @param batches 已完成的 provider 批次数。
         * @param selectedFiles 本次路径过滤中的文件数量。
         * @param fingerprint 本次使用的配置指纹。
         * @return 包含失败状态和累计统计的有序 map。
         */
        private Map<String, Object> failure(String reason, int embedded, int batches,
                                             int selectedFiles, String fingerprint) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("vectorized", false);
            result.put("reason", reason);
            result.put("embedded", embedded);
            result.put("batches", batches);
            result.put("selected_files", selectedFiles);
            result.put("fingerprint", fingerprint);
            return result;
        }

        /**
         * 读取 provider/index 结果中的整数统计字段。
         *
         * @param value 可能是 Number 或数字字符串的字段值。
         * @return 转换后的整数。
         * @throws NumberFormatException value 不是合法整数时抛出。
         */
        private int number(Object value) {
            return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
        }

        /**
         * 校验 embedding 基础地址并拼接 OpenAI 兼容的 {@code /embeddings} 路径。
         * 只接受 HTTP/HTTPS、主机存在且不含用户信息、查询、fragment 或 path，防止配置把请求重定向到未预期端点。
         *
         * @param raw 配置中的基础 URL。
         * @return provider embeddings endpoint。
         * @throws IllegalArgumentException URL 不是符合上述约束的 HTTP(S) 基础地址时抛出。
         */
        private URI endpoint(String raw) {
            try {
                URI base = new URI(raw == null ? "" : raw.trim().replaceAll("/+$", ""));
                String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(java.util.Locale.ROOT);
                if (!("http".equals(scheme) || "https".equals(scheme)) || base.getHost() == null
                        || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
                    throw new IllegalArgumentException("embedding base_url is invalid");
                }
                return URI.create(base + "/embeddings");
            } catch (Exception error) {
                throw new IllegalArgumentException("embedding base_url is invalid", error);
            }
        }

        /**
         * 把 JSON 数组形式的 embedding 转为 PostgreSQL/pgvector 可接受的数组文字。
         * 空数组、非数字元素和 NaN/Infinity 都返回空字符串，由批处理逻辑视为无效 provider 响应。
         *
         * @param embedding provider 响应中的 embedding 节点。
         * @return 形如 {@code [0.1,0.2]} 的有限浮点数组文字；输入无效时返回空字符串。
         */
        private String vector(JsonNode embedding) {
            if (!embedding.isArray() || embedding.isEmpty()) return "";
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < embedding.size(); i++) {
                JsonNode value = embedding.get(i);
                if (!value.isNumber() || !Double.isFinite(value.asDouble())) return "";
                if (i > 0) result.append(',');
                result.append(value.asDouble());
            }
            return result.append(']').toString();
        }

        /**
         * 计算 provider、基础地址和模型的 SHA-256 指纹。
         * API key 故意不参与指纹：换密钥不应让已有向量失效，但切换服务商、地址或模型必须重新向量化。
         *
         * @param config 当前 embedding 配置。
         * @return 供索引有效性判断使用的 64 位十六进制指纹。
         * @throws IllegalStateException JDK 不支持 SHA-256 时抛出。
         */
        private String fingerprint(EmbeddingRuntimeConfig.Config config) {
            return EmbeddingFingerprint.of(config.provider(), config.baseUrl(), config.model());
        }

        /**
         * 提取可用于任务结果的异常说明；没有消息时退回异常类名。
         * 该方法不拼接堆栈，避免把底层实现细节直接放进任务响应。
         *
         * @param error 捕获到的异常。
         * @return 非空错误说明或异常简单类名。
         */
        private static String safeMessage(Exception error) {
            String message = error.getMessage();
            return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
        }
    }
}
