import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 检查 Agent Drive 后端公开健康端点，并以进程退出码表示检查结果。
 */
public final class HealthCheck {
    /**
     * 阻止健康检查脚本被实例化。
     */
    private HealthCheck() {
    }

    /**
     * 使用命令行传入的服务地址执行健康检查。
     *
     * @param args 第一个参数为服务根地址，省略时使用本机 8000 端口
     * @throws IOException 网络请求或响应读取失败
     * @throws InterruptedException 当前线程在等待响应时被中断
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        String baseUrl = args.length == 0 ? "http://127.0.0.1:8000" : args[0];
        HttpResponse<String> response = request(baseUrl);
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            System.out.println("backend health: " + response.body());
            return;
        }
        System.err.println("backend health failed: HTTP " + response.statusCode());
        System.exit(1);
    }

    /**
     * 请求服务的健康端点。
     *
     * @param baseUrl 服务根地址
     * @return 后端返回的 HTTP 响应
     * @throws IOException 网络请求或响应读取失败
     * @throws InterruptedException 当前线程在等待响应时被中断
     */
    private static HttpResponse<String> request(String baseUrl)
            throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(baseUrl) + "/api/v1/health"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 规范化服务根地址，避免拼接健康路径时产生双斜杠。
     *
     * @param baseUrl 原始服务根地址
     * @return 去除末尾斜杠后的服务根地址
     */
    private static String trimTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
