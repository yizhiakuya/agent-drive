package com.agentdrive.net;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 按服务端环境变量创建 JDK HTTP 客户端，并统一执行 HTTP(S) 代理和直连例外规则。
 * 客户端禁止自动跟随重定向，避免 provider 探测请求意外转发凭据。
 */
public final class HttpClientSupport {
    /** 工具类不允许实例化。 */
    private HttpClientSupport() {
    }

    /**
     * 创建连接超时、重定向和代理策略已经固定的 HTTP 客户端构建器。
     * @param timeout 单次连接建立的超时时间。
     * @return 配置完成、尚未调用 {@code build()} 的 JDK 客户端构建器。
     */
    public static java.net.http.HttpClient.Builder builder(Duration timeout) {
        return java.net.http.HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .proxy(new EnvironmentProxySelector());
    }

    /** 根据 HTTP_PROXY、HTTPS_PROXY 和 NO_PROXY 为每个 URI 选择代理。 */
    private static final class EnvironmentProxySelector extends ProxySelector {
        private final Proxy http = proxy(environment("HTTP_PROXY", "http_proxy"));
        private final Proxy https = proxy(environment("HTTPS_PROXY", "https_proxy"));
        private final Proxy fallbackHttps = https == null ? http : https;
        private final List<String> noProxy = noProxy(environment("NO_PROXY", "no_proxy"));

        /**
         * 为 HTTP 或 HTTPS URI 选择对应环境变量中的代理；localhost、回环地址和 NO_PROXY 主机直连。
         * @param uri 要发送请求的 URI。
         * @return 单元素代理列表，或只包含 {@link Proxy#NO_PROXY} 的直连列表。
         */
        @Override
        public List<Proxy> select(URI uri) {
            if (uri == null) throw new IllegalArgumentException("uri must not be null");
            String host = uri.getHost();
            if (host == null || bypass(host)) return List.of(Proxy.NO_PROXY);
            Proxy selected = "https".equalsIgnoreCase(uri.getScheme()) ? fallbackHttps : http;
            return selected == null ? List.of(Proxy.NO_PROXY) : List.of(selected);
        }

        /**
         * 保持空实现，让 JDK 将代理连接异常交回发起请求的调用方处理。
         * @param uri 连接失败的目标 URI。
         * @param address JDK 选择的代理地址。
         * @param error 连接失败原因。
         */
        @Override
        public void connectFailed(URI uri, SocketAddress address, java.io.IOException error) {
            // The JDK client reports the request failure to the caller.
        }

        /**
         * 判断主机是否应绕过代理，支持回环地址、精确主机名、域名后缀和 {@code *}。
         * @param rawHost 待匹配的主机名。
         * @return 应直连时为 {@code true}。
         */
        private boolean bypass(String rawHost) {
            String host = rawHost.toLowerCase(Locale.ROOT);
            if (host.equals("localhost") || host.equals("::1") || host.startsWith("127.")) return true;
            for (String item : noProxy) {
                if (item.equals("*")) return true;
                if (item.startsWith(".")) {
                    if (host.endsWith(item) || host.equals(item.substring(1))) return true;
                } else if (host.equals(item) || host.endsWith("." + item)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 解析 NO_PROXY 逗号列表，去除空项、IPv6 方括号和可选端口。
         * @param raw 环境变量原文。
         * @return 小写主机匹配项列表。
         */
        private static List<String> noProxy(String raw) {
            if (raw == null || raw.isBlank()) return List.of();
            List<String> result = new ArrayList<>();
            for (String item : raw.split(",")) {
                String value = item.trim().toLowerCase(Locale.ROOT);
                if (value.isBlank()) continue;
                if (value.startsWith("[")) {
                    int closing = value.indexOf(']');
                    if (closing >= 0) value = value.substring(1, closing);
                } else if (value.indexOf(':') == value.lastIndexOf(':') && value.lastIndexOf(':') > 0) {
                    value = value.substring(0, value.lastIndexOf(':'));
                }
                result.add(value);
            }
            return List.copyOf(result);
        }

        /**
         * 读取大写环境变量，并在未设置时回退到小写名称。
         * @param upper 大写环境变量名。
         * @param lower 小写环境变量名。
         * @return 去掉首尾空白后的值；两者都未设置时返回空字符串。
         */
        private static String environment(String upper, String lower) {
            String value = System.getenv(upper);
            if (value == null || value.isBlank()) value = System.getenv(lower);
            return value == null ? "" : value.trim();
        }

        /**
         * 校验代理 URL 只包含 HTTP(S) scheme、主机和合法端口，并转换为 JDK HTTP 代理。
         * 代理 URL 含凭据、路径、查询或非法端口时立即抛出配置错误。
         * @param raw 代理环境变量原文。
         * @return JDK HTTP 代理；原文为空时返回 {@code null}。
         */
        private static Proxy proxy(String raw) {
            if (raw == null || raw.isBlank()) return null;
            try {
                URI uri = URI.create(raw);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if (!(scheme.equals("http") || scheme.equals("https"))
                        || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getQuery() != null || uri.getFragment() != null
                        || (uri.getPath() != null && !uri.getPath().isBlank())) {
                    throw new IllegalArgumentException("proxy must be an HTTP(S) URL without credentials or path");
                }
                int port = uri.getPort() > 0 ? uri.getPort() : 80;
                if (port > 65535) throw new IllegalArgumentException("proxy port is invalid");
                return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(uri.getHost(), port));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("HTTP(S)_PROXY is invalid", error);
            }
        }
    }
}
