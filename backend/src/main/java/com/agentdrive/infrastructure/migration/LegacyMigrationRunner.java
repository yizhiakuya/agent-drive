package com.agentdrive.infrastructure.migration;

import com.agentdrive.infrastructure.AppProperties;
import com.agentdrive.infrastructure.LlmApiKeyCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 在 {@code migrate} profile 下运行的一次性 legacy 单用户数据迁移器。
 * 默认只读取并报告 legacy 文件树、JSON 元数据和 SQLite 数量；只有同时启用导入开关并提供
 * {@code IMPORT_LEGACY_DATA} 确认值时，才会复制文件、写入空的 Java PostgreSQL 数据库并排入重建任务。
 * 普通 API 或 Worker profile 不会注册此组件，因此不会意外修改 legacy 数据。
 */
@Component
@Profile("migrate")
public final class LegacyMigrationRunner implements CommandLineRunner {
    private static final String CONFIRMATION = "IMPORT_LEGACY_DATA";
    private static final Set<String> INTERNAL_NAMES = Set.of(
            ".index", ".trash", ".storage.lock");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;
    private final ConfigurableApplicationContext applicationContext;

    @Value("${migration.legacy-data-dir:../backend/data}")
    private String legacyDataDir;

    @Value("${migration.legacy-system-dir:../backend/system}")
    private String legacySystemDir;

    @Value("${migration.apply:false}")
    private boolean apply;

    @Value("${migration.confirm:}")
    private String confirmation;

    /**
     * 创建迁移器并保存数据库、JSON、配置和应用生命周期依赖。
     * @param jdbc 用于检查目标库并执行导入 SQL 的 JDBC 模板。
     * @param objectMapper 用于读取 legacy JSON 和输出迁移报告的 JSON 映射器。
     * @param properties 提供 Java 目标数据目录和 LLM 配置加密密钥的应用配置。
     * @param applicationContext 用于迁移结束时返回进程退出码的 Spring 应用上下文。
     */
    public LegacyMigrationRunner(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            AppProperties properties,
            ConfigurableApplicationContext applicationContext
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.applicationContext = applicationContext;
    }

    /**
     * 执行迁移命令的 dry-run、确认校验和导入分支。
     * 先把配置路径转为绝对规范路径并调用 {@link #inspect(Path, Path, Path)}；未启用 {@code apply} 时只输出报告并以
     * 退出码 0 结束。启用导入但确认值不匹配会进入失败分支；确认通过后执行导入、输出导入报告并以 0 退出。
     * 任意异常都会打印到标准错误并以退出码 1 结束，避免把部分迁移误报为成功。
     * @param args Spring Boot 传入的命令行参数；迁移逻辑不直接读取这些参数。
     */
    @Override
    public void run(String... args) {
        try {
            Path source = absolute(legacyDataDir);
            Path system = absolute(legacySystemDir);
            Path target = absolute(properties.dataDir());
            MigrationInput input = inspect(source, system, target);
            if (!apply) {
                print(report(input, null, false));
                stop(0);
                return;
            }
            if (!CONFIRMATION.equals(confirmation)) {
                throw new IllegalStateException(
                        "migration.apply requires migration.confirm=" + CONFIRMATION);
            }
            UUID ownerId = importAll(input, target);
            print(report(input, ownerId, true));
            stop(0);
        } catch (Exception error) {
            error.printStackTrace(System.err);
            stop(1);
        }
    }

    /**
     * 读取并校验迁移源，生成 dry-run 和正式导入共用的输入快照。
     * 要求源目录存在且不允许源、目标互相嵌套；随后扫描源树统计目录、文件和字节数，读取必需的
     * {@code auth.json} 以及可选的 provider 配置和 upload dedupe 索引，并从 SQLite 统计 jobs/schedules 数量。
     * 这些读取只产生 {@link MigrationInput}，不会写入数据库或目标文件树。
     * @param source legacy 用户可见文件树。
     * @param system legacy 系统元数据目录。
     * @param target Java 目标数据目录，仅用于检查不能与源目录重叠。
     * @return 包含路径、扫描统计、JSON 节点和 SQLite 计数的迁移输入快照。
     * @throws IOException 读取文件树或 JSON 文件失败时抛出。
     * @throws SQLException 读取 SQLite 任务/计划数量失败时抛出。
     * @throws IllegalArgumentException 源目录缺失、源/目标重叠或必需元数据无效时抛出。
     */
    private MigrationInput inspect(Path source, Path system, Path target) throws IOException, SQLException {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("legacy data directory does not exist: " + source);
        }
        if (target.startsWith(source) || source.startsWith(target)) {
            throw new IllegalArgumentException(
                    "legacy and target data directories must be separate: " + source + " / " + target);
        }
        ScanStats stats = scan(source);
        JsonNode auth = readJson(system.resolve("auth.json"), true);
        JsonNode config = readJson(system.resolve("agent-config.json"), false);
        JsonNode uploadIndex = readJson(system.resolve("upload-index.json"), false);
        int devices = auth == null ? 0 : auth.path("device_tokens").size();
        int dedupeEntries = uploadIndex == null ? 0 : uploadIndex.path("by_md5").size();
        int jobs = sqliteCount(system.resolve("tasks.sqlite3"), "jobs");
        int schedules = sqliteCount(system.resolve("tasks.sqlite3"), "schedules");
        return new MigrationInput(source, system, target, stats, auth, config, uploadIndex,
                devices, dedupeEntries, jobs, schedules);
    }

    /**
     * 将迁移快照复制到新 owner 目录，并在一个数据库事务中导入全部 owner 数据。
     * 导入前要求 legacy 密码哈希存在、Java 数据库为空且目标目录为空；复制后重新扫描目标文件并计算 MD5/SHA-256，
     * 再依次导入 owner、设备、provider/embedding 配置、文件元数据、去重索引、SQLite 任务/计划及任务事件，最后排入
     * {@code index.rebuild}。数据库导入异常会标记事务回滚，任何后续异常都会删除本次目标树。
     * @param input 已完成读取和扫描的 legacy 输入快照。
     * @param target Java owner 文件根目录。
     * @return 新建 owner 的 UUID。
     * @throws Exception 校验、文件复制、哈希、数据库导入或事务提交失败时抛出。
     */
    private UUID importAll(MigrationInput input, Path target) throws Exception {
        if (input.auth() == null || input.auth().path("password_hash").asText("").isBlank()) {
            throw new IllegalArgumentException("legacy auth.json has no password_hash");
        }
        ensureDatabaseIsEmpty();
        ensureTargetIsEmpty(target);

        UUID ownerId = UUID.randomUUID();
        Path ownerRoot = target.resolve(ownerId.toString());
        Files.createDirectories(target);
        try {
            copyLegacyTree(input.source(), ownerRoot);
            List<FileEntry> files = scanFiles(ownerRoot);
            TransactionTemplate transaction = new TransactionTemplate(
                    new DataSourceTransactionManager(jdbc.getDataSource()));
            transaction.executeWithoutResult(status -> {
                try {
                    importOwner(ownerId, input.auth());
                    importDevices(ownerId, input.auth());
                    importProviderConfig(ownerId, input.config());
                    importFiles(ownerId, files);
                    importDedupe(ownerId, input.uploadIndex(), files);
                    importTasks(ownerId, input.system().resolve("tasks.sqlite3"));
                    importSchedules(ownerId, input.system().resolve("tasks.sqlite3"));
                    enqueueRebuild(ownerId);
                } catch (Exception error) {
                    status.setRollbackOnly();
                    throw new MigrationException("legacy database import failed", error);
                }
            });
            return ownerId;
        } catch (Exception error) {
            deleteTree(target);
            throw error;
        }
    }

    /**
     * 检查 Java 数据库是否满足一次性导入的空库门禁。
     * SQL 检查 {@code users}、{@code files}、{@code tasks} 和 {@code task_schedules}；任一表已有记录都拒绝导入，
     * 防止 legacy 数据与现有 owner、文件或任务状态混合。
     * @throws IllegalStateException 目标数据库包含受保护业务记录时抛出。
     */
    private void ensureDatabaseIsEmpty() {
        Integer users = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);
        if (users != null && users != 0) {
            throw new IllegalStateException("Java database already contains users; refusing import");
        }
        for (String table : List.of("files", "tasks", "task_schedules")) {
            Integer rows = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
            if (rows != null && rows != 0) {
                throw new IllegalStateException("Java database is not empty: " + table);
            }
        }
    }

    /**
     * 检查 Java 文件目标根是否为空。
     * 目标不存在时允许后续创建；目标存在时必须是非符号链接目录且不能包含任何子项，避免导入覆盖已有文件。
     * @param target Java owner 文件根目录。
     * @throws IOException 枚举目标目录失败时抛出。
     * @throws IllegalArgumentException 目标是文件、符号链接或非空目录时抛出。
     */
    private void ensureTargetIsEmpty(Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("migration target is not a directory: " + target);
            }
            try (var children = Files.list(target)) {
                if (children.findAny().isPresent()) {
                    throw new IllegalArgumentException("migration target must be empty: " + target);
                }
            }
        }
    }

    /**
     * 导入单一 legacy owner 的账号记录。
     * SQL 将随机生成的 owner UUID、固定用户名 {@code owner} 和 legacy 的 {@code password_hash} 写入 {@code users}。
     * @param ownerId 新 owner 的 UUID。
     * @param auth 已读取的 legacy {@code auth.json} 对象。
     */
    private void importOwner(UUID ownerId, JsonNode auth) {
        jdbc.update("""
                INSERT INTO users(id, username, password_hash)
                VALUES (?, 'owner', ?)
                """, ownerId, auth.path("password_hash").asText());
    }

    /**
     * 从 {@code auth.json.device_tokens} 导入 legacy 设备。
     * 只有对象形状的令牌集合会被遍历；缺少设备 ID 时使用 {@code legacy-} 加令牌键前 12 个字符，名称和时间戳使用
     * legacy 值或默认值。每台设备写入 legacy platform、空 model/app version、空同步状态以及设备令牌哈希字段。
     * @param ownerId 新 owner 的 UUID。
     * @param auth 已读取的 legacy {@code auth.json} 对象。
     */
    private void importDevices(UUID ownerId, JsonNode auth) {
        JsonNode tokens = auth.path("device_tokens");
        if (!tokens.isObject()) return;
        tokens.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            String externalId = value.path("device_id").asText("");
            if (externalId.isBlank()) externalId = "legacy-" + entry.getKey().substring(0, 12);
            String name = value.path("name").asText("Legacy device");
            double created = value.path("created_at").asDouble(0);
            double lastUsed = value.path("last_used").asDouble(created);
            jdbc.update("""
                    INSERT INTO devices(
                        user_id, external_device_id, device_token_hash, name, platform,
                        model, app_version, sync_state, created_at, last_seen_at
                    ) VALUES (?, ?, ?, ?, 'legacy', '', '', '{}'::jsonb, to_timestamp(?), to_timestamp(?))
                    """, ownerId, externalId, entry.getKey(), name, created, Math.max(created, lastUsed));
        });
    }

    /**
     * 导入 provider 和 embedding 配置，并在写入前加密 API key。
     * provider 配置只有 type 和 model 非空时写入 {@code llm_provider_configs}；非空 key 使用应用 LLM 配置密钥加密，
     * 同时保存 UTF-8 API key 的 SHA-256 指纹。embedding 配置写入 {@code agent_preferences} 的 {@code embeddings}
     * JSON，provider 默认 {@code jina}，密文再转为 Base64；配置 JSON 无法序列化时以 {@link MigrationException} 失败。
     * @param ownerId 新 owner 的 UUID。
     * @param config legacy {@code agent-config.json} 中的配置对象，可为 {@code null}。
     */
    private void importProviderConfig(UUID ownerId, JsonNode config) {
        if (config == null || !config.isObject()) return;
        String provider = config.path("type").asText("").trim();
        String baseUrl = config.path("base_url").asText("").trim();
        String model = config.path("model").asText("").trim();
        String apiKey = config.path("api_key").asText("");
        if (!provider.isBlank() && !model.isBlank()) {
            byte[] encrypted = apiKey.isBlank() ? null : new LlmApiKeyCipher(properties.llmConfigKey()).encrypt(apiKey);
            jdbc.update("""
                    INSERT INTO llm_provider_configs(
                        user_id, provider, base_url, model, encrypted_api_key, api_key_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """, ownerId, provider, baseUrl, model, encrypted,
                    apiKey.isBlank() ? "" : sha256(apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        JsonNode embeddings = config.path("embeddings");
        if (embeddings.isObject()) {
            String embeddingKey = embeddings.path("api_key").asText("");
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("provider", embeddings.path("provider").asText("jina"));
            value.put("base_url", embeddings.path("base_url").asText(""));
            value.put("model", embeddings.path("model").asText(""));
            value.put("encrypted_api_key", embeddingKey.isBlank() ? ""
                    : java.util.Base64.getEncoder().encodeToString(
                    new LlmApiKeyCipher(properties.llmConfigKey()).encrypt(embeddingKey)));
            try {
                jdbc.update("""
                        INSERT INTO agent_preferences(user_id, preference_key, value)
                        VALUES (?, 'embeddings', CAST(? AS jsonb))
                        """, ownerId, objectMapper.writeValueAsString(value));
            } catch (IOException error) {
                throw new MigrationException("cannot serialize embedding configuration", error);
            }
        }
    }

    /**
     * 将扫描出的文件和目录写入 owner-scoped 文件元数据。
     * 目录先于文件按公共路径排序写入；所有记录从 revision 1 开始。普通文件额外写入一条 revision 1 的
     * {@code file_revisions} 记录，目录保留大小 0 且不写内容哈希。
     * @param ownerId 新 owner 的 UUID。
     * @param entries 复制后扫描得到、包含路径、类型、大小和哈希的文件条目。
     */
    private void importFiles(UUID ownerId, List<FileEntry> entries) {
        entries.stream()
                .sorted(Comparator.comparing(FileEntry::directory).reversed()
                        .thenComparing(FileEntry::path))
                .forEach(entry -> {
                    UUID fileId = UUID.randomUUID();
                    jdbc.update("""
                            INSERT INTO files(
                                id, user_id, path, is_dir, size_bytes, revision, content_md5, content_sha256
                            ) VALUES (?, ?, ?, ?, ?, 1, ?, ?)
                            """, fileId, ownerId, entry.path(), entry.directory(), entry.size(),
                            entry.md5(), entry.sha256());
                    if (!entry.directory()) {
                        jdbc.update("""
                                INSERT INTO file_revisions(
                                    file_id, revision, size_bytes, content_md5, content_sha256
                                ) VALUES (?, 1, ?, ?, ?)
                                """, fileId, entry.size(), entry.md5(), entry.sha256());
                    }
                });
    }

    /**
     * 从文件 MD5 和 legacy upload-index 恢复免传去重记录。
     * 默认按公共路径的首个同 MD5 文件建立候选；若 {@code by_md5} 是对象，则只采纳索引路径存在、条目为文件且
     * 索引键与重新计算的 MD5 匹配的记录，并在有有效索引时以其替代默认候选。写入 {@code upload_dedup} 时固定
     * revision 1、{@code verified=true}，同 owner/MD5 冲突则更新路径和校验状态。
     * @param ownerId 新 owner 的 UUID。
     * @param uploadIndex legacy {@code upload-index.json} 对象，可为 {@code null}。
     * @param entries 复制后重新扫描得到的文件条目。
     */
    private void importDedupe(UUID ownerId, JsonNode uploadIndex, List<FileEntry> entries) {
        Map<String, FileEntry> byMd5 = new LinkedHashMap<>();
        for (FileEntry entry : entries) {
            if (!entry.directory() && entry.md5() != null) byMd5.putIfAbsent(entry.md5(), entry);
        }
        if (uploadIndex != null && uploadIndex.path("by_md5").isObject()) {
            Map<String, FileEntry> indexed = new LinkedHashMap<>();
            uploadIndex.path("by_md5").fields().forEachRemaining(item -> {
                JsonNode value = item.getValue();
                String path = value.path("path").asText("");
                FileEntry entry = entries.stream().filter(candidate -> candidate.path().equals(path)).findFirst().orElse(null);
                if (entry != null && !entry.directory() && item.getKey().equalsIgnoreCase(entry.md5())) {
                    indexed.putIfAbsent(entry.md5(), entry);
                }
            });
            if (!indexed.isEmpty()) byMd5 = indexed;
        }
        for (FileEntry entry : byMd5.values()) {
            jdbc.update("""
                    INSERT INTO upload_dedup(user_id, content_md5, path, file_revision, verified)
                    VALUES (?, ?, ?, 1, true)
                    ON CONFLICT (user_id, content_md5) DO UPDATE SET
                        path = EXCLUDED.path, file_revision = EXCLUDED.file_revision, verified = true,
                        updated_at = now()
                    """, ownerId, entry.md5(), entry.path());
        }
    }

    /**
     * 从 legacy SQLite 的 {@code jobs} 表导入任务及其后续事件。
     * 任务按 {@code created_at} 升序读入，先为原始 ID 建立 UUID 映射，再按父子关系写入 owner-scoped {@code tasks}。
     * legacy {@code running} 映射为 {@code queued}、{@code cancelling} 映射为 {@code cancelled}，未知状态映射为
     * {@code failed}；活跃去重键只保留首个任务，重复键置空。时间、尝试次数、进度和 JSON 字段按数据库导入约定归一化，
     * 随后调用 {@link #importTaskEvents(Path, Map)} 恢复事件。
     * @param ownerId 新 owner 的 UUID。
     * @param sqlite legacy {@code tasks.sqlite3} 文件。
     * @throws SQLException 读取 SQLite jobs 或写入 PostgreSQL 任务失败时抛出。
     */
    private void importTasks(UUID ownerId, Path sqlite) throws SQLException {
        if (!Files.isRegularFile(sqlite, LinkOption.NOFOLLOW_LINKS)) return;
        List<LegacyJob> jobs = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlite);
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM jobs ORDER BY created_at ASC");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                jobs.add(new LegacyJob(
                        rows.getString("id"), rows.getString("type"), rows.getString("lane"),
                        rows.getString("status"), rows.getString("payload_json"), rows.getString("result_json"),
                        rows.getString("error"), rows.getInt("priority"), rows.getString("dedupe_key"),
                        rows.getString("resource_key"), rows.getString("parent_id"), rows.getString("origin"),
                        rows.getInt("attempts"), rows.getInt("max_attempts"), rows.getDouble("run_after"),
                        rows.getInt("cancel_requested"), rows.getInt("progress_current"),
                        rows.getInt("progress_total"), rows.getString("progress_message"),
                        rows.getDouble("created_at"), rows.getDouble("updated_at"),
                        nullableDouble(rows, "started_at"), nullableDouble(rows, "finished_at")));
            }
        }
        Map<String, UUID> ids = new HashMap<>();
        for (LegacyJob job : jobs) ids.put(job.id(), uuidOrRandom(job.id()));
        Set<String> activeDedupe = new HashSet<>();
        for (LegacyJob job : jobs) {
            UUID id = ids.get(job.id());
            UUID parent = job.parentId() == null ? null : ids.get(job.parentId());
            String status = importedStatus(job.status());
            String dedupe = job.dedupeKey();
            if (dedupe != null && !dedupe.isBlank() && isActive(status) && !activeDedupe.add(dedupe)) dedupe = null;
            jdbc.update("""
                    INSERT INTO tasks(
                        id, parent_id, user_id, kind, lane, status, dedupe_key, payload, result, error,
                        attempt, max_attempts, available_at, priority, resource_key, origin,
                        cancel_requested, progress_current, progress_total, progress_message,
                        started_at, finished_at, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?,
                              to_timestamp(?), ?, ?, ?, ?, ?, ?, ?, to_timestamp(?), to_timestamp(?),
                              to_timestamp(?), to_timestamp(?))
                    """, id, parent, ownerId, job.type(), job.lane(), status, dedupe,
                    jsonOrEmpty(job.payloadJson()), jsonOrNull(job.resultJson()), job.error(),
                    Math.max(0, job.attempts()), Math.max(1, job.maxAttempts()),
                    Math.max(0, job.runAfter()), Math.max(0, job.priority()), job.resourceKey(),
                    blankDefault(job.origin(), "legacy"), status.equals("cancelled") || job.cancelRequested() != 0,
                    Math.max(0, job.progressCurrent()), Math.max(0, job.progressTotal()),
                    blankDefault(job.progressMessage(), ""), timestampArg(job.startedAt()), timestampArg(job.finishedAt()),
                    Math.max(0, job.createdAt()), Math.max(0, job.updatedAt()));
        }
        importTaskEvents(sqlite, ids);
    }

    /**
     * 按 legacy 事件 ID 顺序导入 {@code job_events}。
     * 只为已经建立 UUID 映射的任务写入 {@code task_events}；未知任务的事件跳过，空 JSON 归一化为对象，负时间戳归零。
     * @param sqlite legacy {@code tasks.sqlite3} 文件。
     * @param ids legacy job ID 到新任务 UUID 的映射。
     * @throws SQLException 读取 SQLite 事件或写入 PostgreSQL 事件失败时抛出。
     */
    private void importTaskEvents(Path sqlite, Map<String, UUID> ids) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlite);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT job_id, event_type, data_json, created_at FROM job_events ORDER BY id ASC");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID taskId = ids.get(rows.getString("job_id"));
                if (taskId == null) continue;
                jdbc.update("""
                        INSERT INTO task_events(task_id, event_type, payload, created_at)
                        VALUES (?, ?, CAST(? AS jsonb), to_timestamp(?))
                        """, taskId, rows.getString("event_type"), jsonOrEmpty(rows.getString("data_json")),
                        Math.max(0, rows.getDouble("created_at")));
            }
        }
    }

    /**
     * 从 legacy SQLite 的 {@code schedules} 表导入 owner-scoped 计划。
     * 按创建时间升序读取；空类型默认为 {@code cron}，schedule value 同时作为 cron 值和原始 schedule value。
     * 按 owner/name 冲突时更新计划定义、任务参数、启用状态、下次运行时间、重试配置和时区。
     * @param ownerId 新 owner 的 UUID。
     * @param sqlite legacy {@code tasks.sqlite3} 文件；文件不存在时不写入计划。
     * @throws SQLException 读取 SQLite 计划或写入 PostgreSQL 计划失败时抛出。
     */
    private void importSchedules(UUID ownerId, Path sqlite) throws SQLException {
        if (!Files.isRegularFile(sqlite, LinkOption.NOFOLLOW_LINKS)) return;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlite);
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM schedules ORDER BY created_at ASC");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID id = uuidOrRandom(rows.getString("id"));
                String value = rows.getString("schedule_value");
                String kind = blankDefault(rows.getString("schedule_kind"), "cron");
                String cron = value;
                jdbc.update("""
                        INSERT INTO task_schedules(
                            id, user_id, name, cron, schedule_kind, schedule_value, task_kind, lane, payload,
                            enabled, next_run_at, priority, max_attempts, timezone, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, to_timestamp(?), ?, ?, ?,
                                  to_timestamp(?), to_timestamp(?))
                        ON CONFLICT (user_id, name) DO UPDATE SET
                            cron = EXCLUDED.cron, schedule_kind = EXCLUDED.schedule_kind,
                            schedule_value = EXCLUDED.schedule_value, task_kind = EXCLUDED.task_kind,
                            lane = EXCLUDED.lane, payload = EXCLUDED.payload, enabled = EXCLUDED.enabled,
                            next_run_at = EXCLUDED.next_run_at, priority = EXCLUDED.priority,
                            max_attempts = EXCLUDED.max_attempts, timezone = EXCLUDED.timezone,
                            updated_at = EXCLUDED.updated_at
                        """, id, ownerId, rows.getString("name"), cron, kind, value,
                        rows.getString("task_type"), blankDefault(rows.getString("lane"), "default"),
                        jsonOrEmpty(rows.getString("payload_json")), rows.getInt("enabled") != 0,
                        Math.max(0, rows.getDouble("next_run")), Math.max(0, rows.getInt("priority")),
                        Math.max(1, rows.getInt("max_attempts")), blankDefault(rows.getString("timezone"), "UTC"),
                        Math.max(0, rows.getDouble("created_at")), Math.max(0, rows.getDouble("updated_at")));
            }
        }
    }

    /**
     * 为迁移后的 owner 排入一次 {@code index.rebuild} 任务。
     * SQL 将任务放入 {@code index} lane，状态设为 {@code queued}、来源设为 {@code migration}，并使用 owner 相关去重键
     * 使该迁移重建任务可识别。
     * @param ownerId 新 owner 的 UUID。
     */
    private void enqueueRebuild(UUID ownerId) {
        jdbc.update("""
                INSERT INTO tasks(
                    id, user_id, kind, lane, status, dedupe_key, payload, origin, available_at
                ) VALUES (?, ?, 'index.rebuild', 'index', 'queued', ?, '{}'::jsonb, 'migration', now())
                """, UUID.randomUUID(), ownerId, "migration:index-rebuild:" + ownerId);
    }

    /**
     * 扫描复制后的 owner 文件树并为每个公开路径生成导入条目。
     * 遍历顺序稳定；内部路径和目录不计算内容哈希，符号链接及其他非目录/普通文件节点直接拒绝。
     * 普通文件读取 1 MiB 分块分别计算 MD5 与 SHA-256，并记录大小；底层 IO 异常包装为迁移异常。
     * @param root 已复制的 owner 文件根目录。
     * @return 目录和文件的公共相对路径、类型、大小及哈希条目。
     * @throws IOException 遍历文件树或读取文件失败时抛出。
     * @throws MigrationException 发现内部路径以外的符号链接或不支持的文件节点时抛出。
     */
    private List<FileEntry> scanFiles(Path root) throws IOException {
        List<FileEntry> result = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            paths.sorted().forEach(path -> {
                if (path.equals(root)) return;
                try {
                    Path relative = root.relativize(path);
                    if (isInternal(relative)) return;
                    if (Files.isSymbolicLink(path)) throw new MigrationException("symlink in migrated tree: " + path);
                    String publicPath = relative.toString().replace('\\', '/');
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        result.add(new FileEntry(publicPath, true, 0, null, null));
                    } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        byte[] md5 = digest(path, "MD5");
                        byte[] sha = digest(path, "SHA-256");
                        result.add(new FileEntry(publicPath, false, Files.size(path), hex(md5), hex(sha)));
                    } else {
                        throw new MigrationException("unsupported file in migrated tree: " + path);
                    }
                } catch (IOException error) {
                    throw new MigrationException("cannot scan migrated tree: " + path, error);
                }
            });
        }
        return result;
    }

    /**
     * 只统计 legacy 源文件树的目录数、普通文件数和文件字节数。
     * 遍历时跳过根节点及内部路径，拒绝符号链接和不支持的节点；该阶段不读取文件内容，也不计算哈希。
     * @param root legacy 数据根目录。
     * @return 扫描得到的目录、文件和字节统计。
     * @throws IOException 遍历目录或读取文件大小失败时抛出。
     * @throws MigrationException 发现符号链接或不支持的文件节点时抛出。
     */
    private ScanStats scan(Path root) throws IOException {
        long directories = 0;
        long files = 0;
        long bytes = 0;
        try (var paths = Files.walk(root)) {
            for (var iterator = paths.iterator(); iterator.hasNext();) {
                Path path = iterator.next();
                if (path.equals(root)) continue;
                Path relative = root.relativize(path);
                if (isInternal(relative)) continue;
                if (Files.isSymbolicLink(path)) throw new MigrationException("symlink in legacy tree: " + path);
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    directories++;
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    files++;
                    bytes += Files.size(path);
                } else {
                    throw new MigrationException("unsupported file in legacy tree: " + path);
                }
            }
        }
        return new ScanStats(directories, files, bytes);
    }

    /**
     * 将 legacy 文件树复制到新 owner 目录，并跳过内部命名空间。
     * 顶层子项按路径排序处理；{@code .index}、{@code .trash}、锁文件以及 upload/copy staging 名称不会进入目标树。
     * 复制完成后由 {@link #scanFiles(Path)} 再次检查节点并计算哈希。
     * @param source legacy 数据根目录。
     * @param target 新 owner 文件根目录。
     * @throws IOException 创建目录、列目录或复制文件失败时抛出。
     */
    private void copyLegacyTree(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var children = Files.list(source)) {
            for (Path child : children.sorted().toList()) {
                if (isInternalName(child.getFileName().toString())) continue;
                copyTree(child, target.resolve(child.getFileName().toString()));
            }
        }
    }

    /**
     * 递归复制一个文件树并保留文件属性。
     * 访问目录前会跳过内部目录及其子树，访问文件时跳过内部命名文件；其余目录按相对路径创建，文件使用
     * {@link StandardCopyOption#COPY_ATTRIBUTES} 复制到目标。
     * @param source 要复制的源目录或文件。
     * @param target 对应的目标目录或文件。
     * @throws IOException 创建目录或复制文件失败时抛出。
     */
    private void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            /**
             * 为非内部目录创建对应的目标目录。
             * @param directory 当前访问的源目录。
             * @param attributes 当前目录属性；复制目录时不单独使用。
             * @return 内部目录返回 {@link FileVisitResult#SKIP_SUBTREE}，其他目录返回 {@link FileVisitResult#CONTINUE}。
             * @throws IOException 创建目标目录失败时抛出。
             */
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (!directory.equals(source) && isInternalName(directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            /**
             * 复制一个非内部文件及其父目录。
             * @param file 当前访问的源文件。
             * @param attributes 当前文件属性；复制时由 NIO 搭配 {@code COPY_ATTRIBUTES} 处理。
             * @return 始终返回 {@link FileVisitResult#CONTINUE}。
             * @throws IOException 创建父目录或复制文件失败时抛出。
             */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (isInternalName(file.getFileName().toString())) return FileVisitResult.CONTINUE;
                Path destination = target.resolve(source.relativize(file));
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 读取迁移源中的 JSON 对象，并区分可选文件缺失和文件内容损坏。
     * 可选文件不存在时返回 {@code null}；必需文件不存在、内容不是 JSON 对象或读取失败时终止迁移。
     *
     * @param path legacy 元数据文件路径。
     * @param required 文件是否必须存在。
     * @return 解析后的 JSON 对象；可选文件缺失时为 {@code null}。
     * @throws IOException 无法读取或解析文件时抛出。
     * @throws IllegalArgumentException 必需文件缺失或 JSON 根节点不是对象时抛出。
     */
    private JsonNode readJson(Path path, boolean required) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            if (required) throw new IllegalArgumentException("missing legacy metadata: " + path);
            return null;
        }
        JsonNode node = objectMapper.readTree(path.toFile());
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("legacy metadata is not an object: " + path);
        }
        return node;
    }

    /**
     * 读取 legacy SQLite 表的行数，用于 dry-run 报告。
     * 文件不存在时按 0 处理；文件存在时执行 {@code SELECT count(*) FROM table}，调用方只传入固定的
     * {@code jobs} 或 {@code schedules} 表名。
     * @param path legacy SQLite 文件路径。
     * @param table 要统计的固定表名。
     * @return 表中的行数；SQLite 文件不存在时为 0。
     * @throws SQLException 打开数据库或执行计数 SQL 失败时抛出。
     */
    private int sqliteCount(Path path, String table) throws SQLException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return 0;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM " + table);
             ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    /**
     * 组装不含密钥和令牌的迁移摘要。
     * 报告区分 {@code dry-run} 与 {@code import}，包含源/系统/目标路径、扫描数量、设备、去重条目、jobs、schedules、
     * provider 是否具备 type/model 以及数据库是否已导入；正式导入时额外返回 owner UUID。
     * @param input 迁移检查阶段生成的输入快照。
     * @param ownerId 正式导入创建的 owner UUID；dry-run 时为 {@code null}。
     * @param imported 是否已经执行正式导入。
     * @return 按稳定插入顺序构造的报告字段映射。
     */
    private Map<String, Object> report(MigrationInput input, UUID ownerId, boolean imported) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("mode", imported ? "import" : "dry-run");
        report.put("legacy_data_dir", input.source().toString());
        report.put("legacy_system_dir", input.system().toString());
        report.put("target_data_dir", input.target().toString());
        report.put("owner_id", ownerId == null ? "" : ownerId.toString());
        report.put("directories", input.stats().directories());
        report.put("files", input.stats().files());
        report.put("bytes", input.stats().bytes());
        report.put("devices", input.devices());
        report.put("legacy_dedupe_entries", input.dedupeEntries());
        report.put("legacy_jobs", input.jobs());
        report.put("legacy_schedules", input.schedules());
        report.put("provider_configured", input.config() != null
                && !input.config().path("type").asText("").isBlank()
                && !input.config().path("model").asText("").isBlank());
        report.put("database_imported", imported);
        return report;
    }

    /**
     * 判断相对路径是否进入不应迁移的内部命名空间。
     * 只要任一组件是内部名称，就跳过该路径及其内容。
     * @param relative 相对于数据根的路径。
     * @return 路径包含内部组件时为 {@code true}。
     */
    private boolean isInternal(Path relative) {
        for (Path part : relative) if (isInternalName(part.toString())) return true;
        return false;
    }

    /**
     * 判断单个文件名是否属于内部文件命名空间。
     * 固定忽略 {@code .index}、{@code .trash}、{@code .storage.lock}，以及 {@code .upload.}、{@code .copy.}、
     * {@code .copy-old.} 开头的临时或回收文件。
     * @param name 要检查的文件或目录名。
     * @return 属于内部命名空间时为 {@code true}。
     */
    private boolean isInternalName(String name) {
        return INTERNAL_NAMES.contains(name) || name.startsWith(".upload.")
                || name.startsWith(".copy.") || name.startsWith(".copy-old.");
    }

    /**
     * 以分块方式计算文件摘要。
     * 使用指定的 {@link MessageDigest} 算法和 1 MiB 缓冲区读取整个文件，返回算法产生的原始字节摘要；调用方用
     * {@link #hex(byte[])} 转换为小写十六进制字符串。未知算法属于运行时不变量破坏，转换为 {@link IllegalStateException}。
     * @param path 要读取的文件路径。
     * @param algorithm 摘要算法名称，例如 {@code MD5} 或 {@code SHA-256}。
     * @return 文件内容的原始摘要字节。
     * @throws IOException 打开或读取文件失败时抛出。
     * @throws IllegalStateException JDK 不支持指定摘要算法时抛出。
     */
    private byte[] digest(Path path, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("digest algorithm unavailable: " + algorithm, impossible);
        }
    }

    /**
     * 将摘要字节编码为小写十六进制文本。
     * 每个字节固定输出两位，因此结果长度始终是输入长度的两倍。
     * @param bytes 摘要字节数组。
     * @return 小写十六进制字符串。
     */
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    /**
     * 计算内存中数据的 SHA-256 指纹并编码为小写十六进制。
     * 迁移 provider API key 时用于生成指纹，不返回或记录原始 key。
     * @param bytes 要摘要的数据字节。
     * @return SHA-256 小写十六进制字符串。
     * @throws IllegalStateException JDK 不支持 SHA-256 时抛出。
     */
    private static String sha256(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 为写入 JSONB 的 legacy 文本提供空对象默认值。
     * {@code null} 或空白文本转换为 {@code {}}，其他文本原样返回并交由 PostgreSQL JSONB 转换校验。
     * @param value legacy JSON 文本。
     * @return 非空原文，或空对象 JSON 文本。
     */
    private static String jsonOrEmpty(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }

    /**
     * 将空白的可选 JSON 结果转换为 SQL {@code NULL}。
     * {@code null} 或空白文本返回 {@code null}，非空文本保持原样供 JSONB 参数转换。
     * @param value legacy JSON 文本。
     * @return 原 JSON 文本，或表示 SQL NULL 的 {@code null}。
     */
    private static Object jsonOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 将 legacy 任务状态归一化为 Java 任务状态。
     * {@code running} 不能在迁移后继续占用旧租约，因此转为 {@code queued}；{@code cancelling} 转为
     * {@code cancelled}；已知的其他状态原样保留；空值或未知值按 {@code failed} 导入。
     * @param value legacy 状态文本。
     * @return Java 任务状态机接受的状态文本。
     */
    private static String importedStatus(String value) {
        if (value == null) return "failed";
        return switch (value) {
            case "running" -> "queued";
            case "cancelling" -> "cancelled";
            case "queued", "retry_wait", "cancelled", "succeeded", "failed" -> value;
            default -> "failed";
        };
    }

    /**
     * 判断任务状态是否仍属于活跃去重范围。
     * 活跃状态包括 {@code queued}、{@code running}、{@code retry_wait} 和 {@code cancelling}；终态不参与重复任务冲突。
     * @param status 要检查的 Java 任务状态。
     * @return 状态属于活跃集合时为 {@code true}。
     */
    private static boolean isActive(String status) {
        return Set.of("queued", "running", "retry_wait", "cancelling").contains(status);
    }

    /**
     * 为 legacy 字符串字段填充空白默认值。
     * {@code null} 或空白值返回 fallback，其他值原样保留。
     * @param value legacy 字符串值。
     * @param fallback 空值时使用的默认值。
     * @return 归一化后的字符串。
     */
    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 解析 legacy ID，无法解析时生成新的 UUID。
     * 这样可保留合法的任务/计划 ID，同时让不符合 UUID 格式的旧数据仍能在导入中建立稳定的一次性映射。
     * @param value legacy ID 文本。
     * @return 解析出的 UUID，或解析失败时新生成的随机 UUID。
     */
    private static UUID uuidOrRandom(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception ignored) {
            return UUID.randomUUID();
        }
    }

    /**
     * 读取可为 SQL NULL 的 SQLite 浮点列。
     * 先读取 double，再通过 {@link ResultSet#wasNull()} 区分真实数值和 NULL，供可选任务时间字段保留缺失语义。
     * @param rows 当前 SQLite 结果集。
     * @param column 要读取的列名。
     * @return 列值，或列为 SQL NULL 时返回 {@code null}。
     * @throws SQLException 读取结果集失败时抛出。
     */
    private static Double nullableDouble(ResultSet rows, String column) throws SQLException {
        double value = rows.getDouble(column);
        return rows.wasNull() ? null : value;
    }

    /**
     * 将 legacy 秒级时间转换为数据库参数。
     * 缺失时间保留为 {@code null}，负数归零，非负值原样返回供 {@code to_timestamp} 使用。
     * @param seconds Unix epoch 秒数，可为 {@code null}。
     * @return 可用于 JDBC 的非负秒数，或 {@code null}。
     */
    private static Object timestampArg(Double seconds) {
        return seconds == null ? null : Math.max(0, seconds);
    }

    /**
     * 将配置路径解析为绝对且规范化的 NIO 路径。
     * @param path 配置中的路径文本。
     * @return 转为绝对路径并消除冗余组件后的路径。
     */
    private static Path absolute(String path) {
        return Path.of(path).toAbsolutePath().normalize();
    }

    /**
     * 将迁移报告以带缩进的 JSON 写到标准输出。
     * @param report 待输出的迁移报告字段。
     * @throws IOException JSON 序列化失败时抛出。
     */
    private void print(Map<String, Object> report) throws IOException {
        System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    /**
     * 结束 Spring 应用上下文并返回迁移结果退出码。
     * dry-run 和成功导入使用 0，校验或导入异常使用 1；退出码由 Spring 的关闭回调提供给启动器。
     * @param code 迁移结果退出码。
     */
    private void stop(int code) {
        SpringApplication.exit(applicationContext, () -> code);
    }

    /**
     * 删除本次迁移创建的整个目标树。
     * 目标不存在时不操作；存在时先删除文件，再删除目录，并在目录访问回调收到错误时原样抛出。
     * 该方法只在导入异常后的清理分支调用。
     * @param root 要递归删除的目标根目录。
     * @throws IOException 删除文件或目录失败时抛出。
     */
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            /**
             * 删除访问到的文件。
             * @param file 当前访问的文件。
             * @param attributes 当前文件属性；删除操作不使用。
             * @return 始终继续遍历。
             * @throws IOException 删除文件失败时抛出。
             */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            /**
             * 在目录遍历完成后删除目录。
             * 先传播遍历阶段的异常，再删除空目录，确保清理失败不会被吞掉。
             * @param directory 当前访问完成的目录。
             * @param error 访问该目录或其子项时产生的异常。
             * @return 删除成功后继续遍历。
             * @throws IOException 遍历错误或删除目录失败时抛出。
             */
            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 保存检查阶段读取的迁移输入。
     * 包含源/系统/目标路径、文件扫描统计、JSON 元数据和用于报告及导入的 jobs、schedules、设备、去重计数。
     */
    private record MigrationInput(
            Path source,
            Path system,
            Path target,
            ScanStats stats,
            JsonNode auth,
            JsonNode config,
            JsonNode uploadIndex,
            int devices,
            int dedupeEntries,
            int jobs,
            int schedules
    ) {}

    /**
     * 保存文件树扫描得到的目录数、普通文件数和总字节数。
     */
    private record ScanStats(long directories, long files, long bytes) {}

    /**
     * 保存一个待导入的公开文件路径及其元数据。
     * 目录没有内容哈希；普通文件同时保存 MD5、SHA-256、大小和公共相对路径。
     */
    private record FileEntry(String path, boolean directory, long size, String md5, String sha256) {}

    /**
     * 暂存从 SQLite {@code jobs} 行读取的完整 legacy 任务字段。
     * 该记录只用于在建立 legacy ID 到新 UUID 映射后执行 owner-scoped PostgreSQL 导入。
     */
    private record LegacyJob(
            String id,
            String type,
            String lane,
            String status,
            String payloadJson,
            String resultJson,
            String error,
            int priority,
            String dedupeKey,
            String resourceKey,
            String parentId,
            String origin,
            int attempts,
            int maxAttempts,
            double runAfter,
            int cancelRequested,
            int progressCurrent,
            int progressTotal,
            String progressMessage,
            double createdAt,
            double updatedAt,
            Double startedAt,
            Double finishedAt
    ) {}

    /**
     * 表示文件树迁移过程中的校验、扫描、复制或序列化失败。
     * 该异常作为运行时异常在事务导入中触发回滚，并由顶层 {@link #run(String...)} 转换为失败退出码。
     */
    private static final class MigrationException extends RuntimeException {
        /**
         * 创建带有迁移失败描述的异常。
         * @param message 迁移失败描述。
         */
        private MigrationException(String message) { super(message); }
        /**
         * 创建带有原始原因的迁移异常。
         * @param message 迁移失败描述。
         * @param cause 导致迁移失败的原始异常。
         */
        private MigrationException(String message, Throwable cause) { super(message, cause); }
    }
}
