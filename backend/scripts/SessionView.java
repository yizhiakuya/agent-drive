import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * 通过 SSH 查询 Megumin 上指定 Agent Drive 会话的脱敏诊断摘要。
 */
public final class SessionView {
    private static final String DEFAULT_HOST = "root@192.168.0.109";
    private static final String DATABASE_ENV = "/opt/agent-drive-java/.env";
    private static final int DEFAULT_MESSAGE_LIMIT = 12;
    private static final int DEFAULT_CONTENT_LIMIT = 260;
    private static final int FULL_CONTENT_LIMIT = 1600;

    /**
     * 解析命令行参数并执行远端会话查询。
     *
     * @param args 第一个参数为 Agent Drive 会话 UUID，可选参数为 --full 或 --host HOST
     * @throws IOException SSH 进程启动或读取失败
     * @throws InterruptedException 当前线程等待 SSH 进程时被中断
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        final Options options;
        try {
            options = parseOptions(args);
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            System.exit(2);
            return;
        }
        String encodedScript = Base64.getEncoder().encodeToString(
                buildRemoteScript(options).getBytes(StandardCharsets.UTF_8)
        );
        Process process = new ProcessBuilder(
                "ssh", options.host(), "echo " + encodedScript + " | base64 -d | bash"
        ).redirectErrorStream(true).start();
        PrintStream console = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        try (BufferedReader output = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output.lines().forEach(console::println);
        }
        console.flush();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * 将用户参数转换为已校验的查询选项。
     *
     * @param args 原始命令行参数
     * @return 包含会话 ID、远端主机、消息数量和输出长度选项的配置
     */
    private static Options parseOptions(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException(
                    "usage: java scripts/SessionView.java SESSION_ID [--full] [--host HOST]"
            );
        }
        UUID sessionId;
        try {
            sessionId = UUID.fromString(args[0]);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("invalid Agent Drive session UUID: " + args[0], error);
        }

        String host = DEFAULT_HOST;
        boolean full = false;
        for (int index = 1; index < args.length; index++) {
            switch (args[index]) {
                case "--full" -> full = true;
                case "--host" -> {
                    if (++index >= args.length || args[index].isBlank()) {
                        throw new IllegalArgumentException("--host requires a value");
                    }
                    host = args[index];
                }
                default -> throw new IllegalArgumentException("unknown option: " + args[index]);
            }
        }
        return new Options(
                sessionId.toString(),
                host,
                full,
                full ? FULL_CONTENT_LIMIT : DEFAULT_CONTENT_LIMIT,
                full ? 0 : DEFAULT_MESSAGE_LIMIT
        );
    }

    /**
     * 构造在服务器上执行的只读 SQL 查询脚本。
     *
     * @param options 已校验的会话查询选项
     * @return 会加载数据库环境、查询会话并按模式输出脱敏结果的 Bash 脚本
     */
    private static String buildRemoteScript(Options options) {
        String sessionId = options.sessionId();
        int contentLimit = options.contentLimit();
        String messageHeading = options.full()
                ? "=== messages (content truncated and token-redacted) ==="
                : "=== messages (latest " + options.messageLimit() + ", content truncated and token-redacted) ===";
        String messageQuery = """
                SELECT recent.id AS message_id, recent.role, coalesce(recent.tool_name,'') AS tool,
                       coalesce(recent.context_source,'') AS context_source,
                       to_char(recent.created_at, 'YYYY-MM-DD HH24:MI:SSOF') AS created_at,
                       left(regexp_replace(regexp_replace(
                         replace(replace(coalesce(recent.content,''), chr(10), ' '), chr(13), ' '),
                         'jina_[A-Za-z0-9_-]+', '[REDACTED]', 'g'),
                         '(sk-|Bearer )[A-Za-z0-9._-]+', '[REDACTED]', 'gi'), %d) AS content
                FROM (
                  SELECT id, role, tool_name, context_source, created_at, content
                  FROM chat_messages
                  WHERE session_id='%s'
                  ORDER BY id DESC%s
                ) recent
                ORDER BY recent.id;
                """.formatted(
                contentLimit,
                sessionId,
                options.full() ? "" : " LIMIT " + options.messageLimit()
        );
        String replayQuery = """
                SELECT id AS replay_id, tool_name,
                       coalesce(arguments->>'action','') AS action,
                       coalesce(arguments->>'operation','') AS operation,
                       coalesce(arguments->>'path','') AS path,
                       left(regexp_replace(regexp_replace(
                         replace(replace(coalesce(output,''), chr(10), ' '), chr(13), ' '),
                         'jina_[A-Za-z0-9_-]+', '[REDACTED]', 'g'),
                         '(sk-|Bearer )[A-Za-z0-9._-]+', '[REDACTED]', 'gi'), %d) AS output
                FROM chat_tool_replays
                WHERE session_id='%s'
                ORDER BY id;
                """.formatted(contentLimit, sessionId);
        return """
                set -euo pipefail
                set -a
                . %s
                set +a
                session_id='%s'
                psql=(docker exec agent-drive-java-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off)
                found=$("${psql[@]}" -Atc "SELECT count(*) FROM chat_sessions WHERE id='$session_id';")
                if [ "$found" -eq 0 ]; then
                  echo "session not found: $session_id"
                  exit 3
                fi
                echo "=== session ==="
                "${psql[@]}" -A -F ' | ' -c "SELECT id AS session_id, coalesce(title,'') AS title, coalesce(last_routed,'') AS last_routed, created_at, updated_at, (SELECT count(*) FROM chat_messages WHERE session_id='$session_id') AS message_count, (SELECT count(*) FROM chat_tool_replays WHERE session_id='$session_id') AS tool_replay_count, (pending_confirmation IS NOT NULL) AS has_pending_confirmation FROM chat_sessions WHERE id='$session_id';"
                echo
                echo "%s"
                "${psql[@]}" -A -F ' | ' -c "%s"
                echo
                echo "=== tool replays (arguments summarized) ==="
                "${psql[@]}" -A -F ' | ' -c "%s"
                echo
                echo "=== trace shape ==="
                "${psql[@]}" -A -F ' | ' -c "SELECT coalesce(last_routed,'') AS last_routed, jsonb_typeof(last_trace) AS last_trace_type, (pending_confirmation IS NOT NULL) AS has_pending_confirmation FROM chat_sessions WHERE id='$session_id';"
                """.formatted(
                DATABASE_ENV,
                sessionId,
                messageHeading,
                messageQuery.replace("\"", "\\\""),
                replayQuery.replace("\"", "\\\"")
        );
    }

    /**
     * 保存会话查看命令需要的不可变选项。
     *
     * @param sessionId 已校验的 Agent Drive 会话 UUID
     * @param host SSH 目标主机
     * @param full 是否显示全部消息
     * @param contentLimit 每条消息最多输出的字符数
     * @param messageLimit 默认模式显示的最近消息条数，完整模式为 0
     */
    private record Options(
            String sessionId,
            String host,
            boolean full,
            int contentLimit,
            int messageLimit
    ) {
    }
}
