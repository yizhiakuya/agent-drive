package com.agentdrive.index;

import com.agentdrive.files.FileStorageService;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.xml.sax.SAXException;

/**
 * 负责把用户文件转换为可检索全文和重叠文本块的索引服务。
 * 小型文本文件直接按 UTF-8 读取，其他文档格式交给 Apache Tika；图片不进入文本抽取链路，必须走视觉描述索引；单文件正文上限为 20 MiB。
 * 每次写入都携带 source revision、抽取器版本和 chunk 版本，避免旧文件内容继续参与向量检索。
 */
@Service
@Profile({"java-files", "java-auth", "java-chat"})
public class IndexingService {
    private static final String EXTRACTOR_VERSION = "java-tika-v1";
    private static final String CHUNK_VERSION = "java-chunk-v1";
    private static final int MAX_TEXT_BYTES = 20 * 1024 * 1024;
    private static final int CHUNK_SIZE = 2000;
    private static final int CHUNK_OVERLAP = 200;
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp");

    private final FileStorageService files;
    private final IndexStore index;

    /**
     * 创建索引服务，并保存文件读取和索引持久化依赖。
     *
     * @param files 用于执行安全路径解析和读取文件内容的存储服务。
     * @param index 用于读取文件 revision、写入全文分块及清理失效记录的索引存储。
     */
    public IndexingService(FileStorageService files, IndexStore index) {
        this.files = files;
        this.index = index;
    }

    /**
     * 抽取并替换一个文件的全文索引。
     * 方法先从索引存储确认文件存在、大小、ID 和 revision，再读取文件；超过 20 MiB 返回 {@code too_large}，抽取失败返回
     * {@code extract_error}，两者都不会写入新的文档。成功时以当前 revision 和版本标识原子替换全文及 chunks。
     *
     * @param userId 文件归属用户的 UUID。
     * @param path 用户存储根目录下的相对文件路径。
     * @return 包含路径、是否成功、状态和正文长度的结果 map。
     * @throws IllegalArgumentException 索引中找不到该文件时抛出。
     * @throws IllegalStateException 存储文件无法读取或抽取器无法处理时可能抛出；抽取阶段的该异常会被转换为失败结果。
     */
    public Map<String, Object> indexFile(UUID userId, String path) {
        Map<String, Object> metadata = index.file(userId, path);
        if (metadata == null) throw new IllegalArgumentException("file not found: " + path);
        if (isImagePath(path)) return result(path, false, "vision_required", 0L, IndexStore.VISION_DOCUMENT_TYPE);
        long size = number(metadata.get("size_bytes"));
        if (size > MAX_TEXT_BYTES) return result(path, false, "too_large", size);
        Path source = files.fileForRead(userId, path);
        String content;
        try {
            content = extract(source, path);
        } catch (IllegalStateException error) {
            return result(path, false, "extract_error", size);
        }
        UUID fileId = UUID.fromString(String.valueOf(metadata.get("id")));
        long revision = number(metadata.get("revision"));
        List<String> chunks = chunks(content);
        index.replaceDocument(userId, fileId, revision, content, EXTRACTOR_VERSION, chunks, CHUNK_VERSION);
        return result(path, true, "indexed", content.length(), IndexStore.TEXT_DOCUMENT_TYPE);
    }

    /**
     * 把视觉模型生成的结构化 JSON 写入当前文件 revision 的全文索引。
     *
     * <p>视觉描述不是直接写入向量列，而是先作为稳定的文档正文切块；这样普通文本检索、
     * 现有 embedding provider 和 revision 失效规则都能复用。调用方应在 Worker 中完成模型
     * 调用后再进入本方法。</p>
     *
     * @param userId 文件归属用户 UUID。
     * @param path 图片相对路径。
     * @param structuredDescription 已通过视觉 schema 校验的 JSON 文本。
     * @return 视觉索引结果，包含路径、状态和正文长度。
     * @throws IllegalArgumentException 文件不存在或描述为空时抛出。
     */
    public Map<String, Object> indexDescription(UUID userId, String path, String structuredDescription) {
        if (structuredDescription == null || structuredDescription.isBlank()) {
            throw new IllegalArgumentException("vision description is empty");
        }
        Map<String, Object> metadata = index.file(userId, path);
        if (metadata == null) throw new IllegalArgumentException("file not found: " + path);
        UUID fileId = UUID.fromString(String.valueOf(metadata.get("id")));
        long revision = number(metadata.get("revision"));
        List<String> chunks = chunks(structuredDescription);
        index.replaceDocument(userId, fileId, revision, IndexStore.VISION_DOCUMENT_TYPE,
                structuredDescription, "vision-description-v1", chunks, "vision-chunk-v1");
        return result(path, true, "vision_indexed", structuredDescription.length(), IndexStore.VISION_DOCUMENT_TYPE);
    }

    /**
     * 遍历用户指定前缀下的全部文件并逐个重建全文索引。
     * 单文件的成功与跳过结果分别计数；向量化由任务处理器在全文重建之后执行，本方法不会调用 embedding provider。
     *
     * @param userId 文件归属用户的 UUID。
     * @param prefix 可选的用户相对路径前缀；为 {@code null} 时处理用户全部文件。
     * @return 包含成功数、跳过数和实际使用前缀的汇总结果。
     */
    public Map<String, Object> rebuild(UUID userId, String prefix) {
        int indexed = 0;
        int skipped = 0;
        List<String> textPaths = new ArrayList<>();
        List<String> visionPaths = new ArrayList<>();
        List<Map<String, Object>> files = index.files(userId, prefix);
        int total = files.size();
        int processed = 0;
        for (Map<String, Object> file : files) {
            String path = String.valueOf(file.get("path"));
            Map<String, Object> result = indexFile(userId, path);
            if (Boolean.TRUE.equals(result.get("indexed"))) {
                indexed++;
                textPaths.add(path);
            } else if ("vision_required".equals(result.get("status"))) {
                visionPaths.add(path);
            } else {
                skipped++;
            }
            processed++;
        }
        return Map.of("indexed", indexed, "skipped", skipped, "processed_files", processed,
                "total_files", total, "prefix", prefix == null ? "" : prefix,
                "text_paths", List.copyOf(textPaths), "vision_paths", List.copyOf(visionPaths));
    }

    /**
     * 删除索引中已经不存在或不再属于该用户文件树的记录。
     * 文件本身和当前全文不会被修改；返回值只统计被索引存储清理的记录数量。
     *
     * @param userId 要清理索引的用户 UUID。
     * @return 包含清理数量的结果 map。
     */
    public Map<String, Object> cleanup(UUID userId) {
        return Map.of("removed", index.cleanup(userId));
    }

    /**
     * 清空 owner 全部文本/视觉向量，保留正文索引和原始文件，供后续任务重新生成。
     * 该入口只由 Worker 调用，不在上传、聊天或普通文件请求内执行。
     *
     * @param userId 文件归属 owner。
     * @return 清空数量和稳定操作状态。
     */
    public Map<String, Object> clearVectors(UUID userId) {
        return Map.of("cleared_vectors", index.clearEmbeddings(userId), "status", "vectors_cleared");
    }

    /**
     * 根据文件扩展名选择文本读取或 Tika 抽取策略。图片在调用方已被识别并拒绝进入此方法，
     * 因此这里不会把图片字节伪装成文本。
     *
     * @param source 已通过存储层安全校验的本地文件路径。
     * @param path 文件相对路径，用于扩展名判断和异常信息。
     * @return 抽取出的纯文本内容。
     * @throws IllegalStateException 文件读取或格式抽取失败时抛出。
     */
    private String extract(Path source, String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        boolean plainText = lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")
                || lower.endsWith(".csv") || lower.endsWith(".json") || lower.endsWith(".xml")
                || lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".java")
                || lower.endsWith(".js") || lower.endsWith(".ts") || lower.endsWith(".tsx")
                || lower.endsWith(".py") || lower.endsWith(".yaml") || lower.endsWith(".yml")
                || lower.endsWith(".log") || lower.endsWith(".properties");
        if (!plainText) return extractWithTika(source, path);
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (MalformedInputException error) {
            return new String(readLimited(source), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("cannot extract text from " + path, error);
        }
    }

    /**
     * 使用 Tika 自动识别非图片文档格式并抽取正文。Tika handler 受 20 MiB 限制，
     * 不配置或调用图片文字识别 parser。
     *
     * @param source 待读取的本地文档路径。
     * @param path 文档相对路径，用作 Tika 资源名和错误上下文。
     * @return Tika handler 收集到的正文。
     * @throws IllegalStateException 文件读取、SAX 解析或 Tika 解析失败时抛出。
     */
    private String extractWithTika(Path source, String path) {
        try (InputStream input = Files.newInputStream(source)) {
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, path);
            BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_BYTES);
            ParseContext context = new ParseContext();
            new AutoDetectParser().parse(input, handler, metadata, context);
            return handler.toString();
        } catch (IOException | SAXException | TikaException error) {
            throw new IllegalStateException("cannot extract document text from " + path, error);
        }
    }

    /**
     * 读取文件字节并执行正文大小上限检查。
     * 该方法只作为非法 UTF-8 文本的有限回退，不绕过索引服务的 20 MiB 限制。
     *
     * @param source 待读取的本地文件路径。
     * @return 文件的原始字节。
     * @throws IllegalArgumentException 文件超过 20 MiB 时抛出。
     * @throws IllegalStateException 文件读取失败时抛出。
     */
    private byte[] readLimited(Path source) {
        try {
            byte[] bytes = Files.readAllBytes(source);
            if (bytes.length > MAX_TEXT_BYTES) throw new IllegalArgumentException("file is too large to index");
            return bytes;
        } catch (IOException error) {
            throw new IllegalStateException("cannot read file for indexing", error);
        }
    }

    /**
     * 按固定字符窗口切分正文，并在相邻窗口之间保留 200 个字符的重叠。
     * 空正文不生成 chunk；非空正文每块最多 2000 个字符，顺序与原文一致。
     *
     * @param content 已抽取的正文。
     * @return 按索引顺序排列的文本块列表。
     */
    private List<String> chunks(String content) {
        List<String> result = new ArrayList<>();
        if (content == null || content.isEmpty()) return result;
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + CHUNK_SIZE);
            result.add(content.substring(start, end));
            if (end == content.length()) break;
            start = end - CHUNK_OVERLAP;
        }
        return result;
    }

    /**
     * 构造单文件索引操作的稳定结果结构。
     * {@code size} 在成功时表示抽取正文长度，在跳过时表示文件字节大小，具体状态由 {@code status} 区分。
     *
     * @param path 文件相对路径。
     * @param indexed 是否已经把正文写入索引。
     * @param status 机器可读状态，例如 {@code indexed}、{@code too_large} 或 {@code extract_error}。
     * @param size 与结果状态对应的大小数值。
     * @return 包含 path、indexed、status 和 size 的有序结果 map。
     */
    private Map<String, Object> result(String path, boolean indexed, String status, long size) {
        return result(path, indexed, status, size,
                IndexStore.TEXT_DOCUMENT_TYPE);
    }

    private Map<String, Object> result(String path, boolean indexed, String status, long size, String vectorType) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path);
        result.put("indexed", indexed);
        result.put("status", status);
        result.put("size", size);
        result.put("vector_type", vectorType);
        return result;
    }

    /** 图片只允许走视觉描述链路，避免生成第二份不受控的文本向量。 */
    public static boolean isImagePath(String path) {
        String normalized = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        int slash = normalized.lastIndexOf('/');
        int dot = normalized.lastIndexOf('.');
        return dot > slash && IMAGE_EXTENSIONS.contains(normalized.substring(dot));
    }

    /**
     * 将索引存储返回的数值字段统一转换为 {@code long}。
     * JDBC 结果可能是 {@link Number}，测试或 JSON map 也可能是数字字符串，因此两种表示都支持。
     *
     * @param value JDBC/JSON 返回的 revision 或大小字段。
     * @return 转换后的长整数。
     * @throws NumberFormatException value 既不是 Number 也不是合法整数文本时抛出。
     */
    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }
}
