package com.itjiang;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * 配置文件解析器.
 * <p>
 * 负责加载位于程序运行目录下的 `proxy.properties` 文件.
 * 如果部分加载失败, 将使用代码中定义的默认值.
 */
public final class Config {

    // --- 默认值 ---
    private static final int DEFAULT_SERVER_PORT = 58001;
    private static final int DEFAULT_LOCAL_PORT = 58080;
    private static final String DEFAULT_SERVER_HOST = "127.0.0.1";
    private static final String DEFAULT_SERVER_CERT_PATH = "./server.crt";
    private static final String DEFAULT_SERVER_KEY_PATH = "./server.key";
    public static final String DEFAULT_SERVER_STATS_PATH = "./stats.json";
    private static final int DEFAULT_MONITOR_PORT = 18080;
    private static final String DEFAULT_MONITOR_HOST = "127.0.0.1";

    // --- 配置项 ---
    public static final int SERVER_PORT;
    public static final String SERVER_HOST;
    public static final int CLIENT_LOCAL_PORT;
    public static final String CLIENT_AUTH_TOKEN;
    public static final List<String> SERVER_AUTH_TOKEN_LIST;
    public static final File SERVER_CERT; // server.crt
    public static final File SERVER_KEY; // server.key
    public static final String SERVER_STATS_PATH;
    public static final int SERVER_MONITOR_PORT;
    public static final String SERVER_MONITOR_HOST;

    static {
        Properties props = new Properties();
        try (InputStream input = new FileInputStream(System.getProperty("config", "proxy.properties"))) {
            props.load(input);
        } catch (IOException ex) {
            throw new RuntimeException("读取配置文件失败，务必检查 proxy.properties 文件是否存在于程序运行目录下", ex);
        }

        SERVER_PORT = parsePort(props, "server.port", DEFAULT_SERVER_PORT);
        SERVER_HOST = props.getProperty("server.host", DEFAULT_SERVER_HOST);

        try {
            String serverCertPath = props.getProperty("server.cert.path");
            if (serverCertPath != null && !serverCertPath.isBlank()) {
                SERVER_CERT = new File(serverCertPath);
            } else {
                SERVER_CERT = new File(DEFAULT_SERVER_CERT_PATH);
            }
        } catch (Exception e) {
            throw new RuntimeException("配置错误: 读取 server.cert.path 失败");
        }
        try {
            String serverKeyPath = props.getProperty("server.key.path");
            if (serverKeyPath != null && !serverKeyPath.isBlank()) {
                SERVER_KEY = new File(serverKeyPath);
            } else {
                SERVER_KEY = new File(DEFAULT_SERVER_KEY_PATH);
            }
        } catch (Exception e) {
            throw new RuntimeException("配置错误: 读取 server.key.path 失败");
        }

        CLIENT_LOCAL_PORT = parsePort(props, "client.local.port", DEFAULT_LOCAL_PORT);

        CLIENT_AUTH_TOKEN = props.getProperty("client.auth.token");
        Objects.requireNonNull(CLIENT_AUTH_TOKEN, "配置错误: client.auth.token 不能为空");

        String serverAuthTokenListStr = props.getProperty("server.auth.token.list", "");
        SERVER_AUTH_TOKEN_LIST = Arrays.stream(serverAuthTokenListStr.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .collect(Collectors.toUnmodifiableList());
        if (SERVER_AUTH_TOKEN_LIST.isEmpty()) {
            throw new RuntimeException("配置错误: server.auth.token.list 不能为空");
        }

        SERVER_STATS_PATH = props.getProperty("server.stats.path", DEFAULT_SERVER_STATS_PATH);
        SERVER_MONITOR_PORT = parsePort(props, "server.monitor.port", DEFAULT_MONITOR_PORT);
        SERVER_MONITOR_HOST = props.getProperty("server.monitor.host", DEFAULT_MONITOR_HOST).trim();
        if (SERVER_MONITOR_HOST.isEmpty()) {
            throw new RuntimeException("配置错误: server.monitor.host 不能为空");
        }

        validateFileExists(SERVER_CERT, "server.cert.path");
    }

    private static int parsePort(Properties props, String key, int defaultValue) {
        String value = props.getProperty(key, String.valueOf(defaultValue));
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) {
                throw new RuntimeException("配置错误: " + key + " 端口范围必须在 1-65535, 当前值: " + value);
            }
            return port;
        } catch (NumberFormatException ex) {
            throw new RuntimeException("配置错误: " + key + " 必须为数字端口, 当前值: " + value, ex);
        }
    }

    private static void validateFileExists(File file, String keyName) {
        if (!file.exists()) {
            throw new RuntimeException("配置错误: " + keyName + " 对应文件不存在: " + file.getAbsolutePath());
        }
    }
}
