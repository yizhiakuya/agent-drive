package com.agentdrive.api;

import com.agentdrive.tasks.TaskWorkerStore;
import com.agentdrive.infrastructure.AppProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提供不需要认证的服务存活探针。
 *
 * <p>该控制器只返回固定的服务标识和 {@code ok=true}，不读取数据库、文件存储或
 * 认证状态，因此可供反向代理和 systemd 在业务依赖不可用时仍然判断 HTTP 进程是否存活。
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {
    private final JdbcTemplate jdbc;
    private final TaskWorkerStore workers;
    private final Path storageRoot;

    /** Production constructor; optional providers keep the liveness slice lightweight. */
    @Autowired
    public HealthController(ObjectProvider<JdbcTemplate> jdbc,
                            ObjectProvider<TaskWorkerStore> workers,
                            ObjectProvider<AppProperties> properties) {
        this(jdbc == null ? null : jdbc.getIfAvailable(),
                workers == null ? null : workers.getIfAvailable(),
                storagePath(properties == null ? null : properties.getIfAvailable()));
    }

    /** Constructor used by focused contract tests. */
    public HealthController(JdbcTemplate jdbc, TaskWorkerStore workers) {
        this(jdbc, workers, Path.of("."));
    }

    /** Constructor with an explicit storage root for readiness contract tests. */
    public HealthController(JdbcTemplate jdbc, TaskWorkerStore workers, Path storageRoot) {
        this.jdbc = jdbc;
        this.workers = workers;
        this.storageRoot = storageRoot;
    }

    /** Constructor used by minimal embedding callers. */
    public HealthController() {
        this((JdbcTemplate) null, (TaskWorkerStore) null, Path.of("."));
    }

    /**
     * 响应 {@code GET /api/v1/health} 探针。
     *
     * @return 包含 {@code ok=true} 和 {@code service=agent-drive} 的 JSON 对象；该响应不代表数据库、Worker 或外部 Provider 已就绪。
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "service", "agent-drive");
    }

    /**
     * 响应 {@code GET /api/v1/ready} 就绪探针。
     * 与固定响应的 liveness 不同，该探针执行数据库探活并读取最近十秒的 Worker 心跳。
     */
    @GetMapping("/ready")
    public Mono<ResponseEntity<Map<String, Object>>> ready() {
        return Mono.fromCallable(this::readiness)
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<Map<String, Object>> readiness() {
        boolean databaseOk = false;
        int workerCount = 0;
        String databaseError = null;
        String workerError = null;

        if (jdbc == null) {
            databaseError = "database probe unavailable";
        } else {
            try {
                Integer value = jdbc.queryForObject("SELECT 1", Integer.class);
                databaseOk = value != null && value == 1;
                if (!databaseOk) databaseError = "database probe returned unexpected value";
            } catch (RuntimeException error) {
                // Readiness is public; never echo JDBC/provider exception text or connection details.
                databaseError = "database unavailable";
            }
        }

        if (workers == null) {
            workerError = "worker heartbeat probe unavailable";
        } else {
            try {
                workerCount = Math.max(0, workers.onlineWorkerCount());
            } catch (RuntimeException error) {
                workerError = "worker heartbeat unavailable";
            }
        }
        boolean workerOk = workerError == null && workerCount > 0;
        boolean storageOk = false;
        long storageFree = 0;
        long storageTotal = 0;
        String storageError = null;
        if (storageRoot == null) {
            storageError = "storage probe unavailable";
        } else {
            try {
                if (!Files.isDirectory(storageRoot)
                        || !Files.isReadable(storageRoot)
                        || !Files.isWritable(storageRoot)) {
                    storageError = "storage directory unavailable";
                } else {
                    var store = Files.getFileStore(storageRoot);
                    storageFree = Math.max(0, store.getUsableSpace());
                    storageTotal = Math.max(0, store.getTotalSpace());
                    storageOk = true;
                }
            } catch (RuntimeException | java.io.IOException error) {
                storageError = "storage unavailable";
            }
        }
        boolean ready = databaseOk && workerOk && storageOk;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ready", ready);
        body.put("service", "agent-drive");
        body.put("checked_at", Instant.now().toString());
        Map<String, Object> database = new LinkedHashMap<>();
        database.put("ok", databaseOk);
        if (databaseError != null) database.put("error", databaseError);
        body.put("database", database);
        Map<String, Object> workers = new LinkedHashMap<>();
        workers.put("ok", workerOk);
        workers.put("online", workerCount);
        workers.put("window_seconds", 10);
        if (workerError != null) workers.put("error", workerError);
        body.put("workers", workers);
        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("ok", storageOk);
        storage.put("free_bytes", storageFree);
        storage.put("total_bytes", storageTotal);
        if (storageError != null) storage.put("error", storageError);
        body.put("storage", storage);
        body.put("backup", backupStatus());
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /** 读取最近可验证的备份摘要；备份异常不改变 API/Worker/storage readiness 判定。 */
    private Map<String, Object> backupStatus() {
        Path backupRoot = storageRoot == null ? null : storageRoot.toAbsolutePath().normalize().resolveSibling("backups");
        Map<String, Object> result = new LinkedHashMap<>();
        if (backupRoot == null || !Files.isDirectory(backupRoot)) {
            result.put("ok", false);
            result.put("error", "backup directory unavailable");
            result.put("retained", 0);
            return result;
        }
        try (var files = Files.list(backupRoot)) {
            List<Path> archives = files
                    .filter(path -> path.getFileName().toString().startsWith("agent-drive-java-")
                            && path.getFileName().toString().endsWith(".tar.gz"))
                    .toList();
            Path latest = archives.stream()
                    .max((left, right) -> {
                        try {
                            return Files.getLastModifiedTime(left).compareTo(Files.getLastModifiedTime(right));
                        } catch (java.io.IOException error) {
                            return 0;
                        }
                    })
                    .orElse(null);
            result.put("retained", archives.size());
            if (latest == null || !Files.isRegularFile(latest.resolveSibling(latest.getFileName() + ".sha256"))) {
                result.put("ok", false);
                result.put("error", latest == null ? "no backup found" : "backup checksum missing");
                return result;
            }
            result.put("ok", true);
            result.put("last_backup_at", Files.getLastModifiedTime(latest).toMillis() / 1000.0);
            return result;
        } catch (java.io.IOException | RuntimeException error) {
            result.put("ok", false);
            result.put("error", "backup status unavailable");
            result.put("retained", 0);
            return result;
        }
    }

    /** 从运行配置解析 owner 文件存储目录；路径不写入 readiness 响应。 */
    private static Path storagePath(AppProperties properties) {
        return properties == null ? Path.of(".") : Path.of(properties.dataDir());
    }
}
