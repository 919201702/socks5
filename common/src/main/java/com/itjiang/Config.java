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
 */
public final class Config {

    private static final int DEFAULT_SERVER_PORT = 58001;
    private static final int DEFAULT_CLIENT_LOCAL_PORT = 58080;
    private static final int DEFAULT_CLIENT_HTTP_PORT = 58081;
    private static final int DEFAULT_CLIENT_HTTPS_PORT = 58082;
    private static final String DEFAULT_SERVER_HOST = "127.0.0.1";
    private static final String DEFAULT_SERVER_CERT_PATH = "./server.crt";
    private static final String DEFAULT_SERVER_KEY_PATH = "./server.key";
    public static final String DEFAULT_SERVER_STATS_PATH = "./stats.json";
    private static final int DEFAULT_MONITOR_PORT = 18080;
    private static final String DEFAULT_MONITOR_HOST = "127.0.0.1";

    public static final int SERVER_PORT;
    public static final String SERVER_HOST;
    public static final String CLIENT_AUTH_TOKEN;
    public static final List<String> SERVER_AUTH_TOKEN_LIST;

    public static final int CLIENT_LOCAL_PORT;
    public static final int CLIENT_HTTP_PORT;
    public static final int CLIENT_HTTPS_PORT;
    public static final boolean CLIENT_SOCKS5_ENABLED;
    public static final boolean CLIENT_HTTP_ENABLED;
    public static final boolean CLIENT_HTTPS_ENABLED;

    public static final File SERVER_CERT;
    public static final File SERVER_KEY;
    public static final String SERVER_STATS_PATH;
    public static final int SERVER_MONITOR_PORT;
    public static final String SERVER_MONITOR_HOST;

    static {
        Properties props = loadProperties();

        SERVER_PORT = parsePort(props, "server.port", DEFAULT_SERVER_PORT);
        SERVER_HOST = getString(props, "server.host", DEFAULT_SERVER_HOST);

        SERVER_CERT = parseFile(props, "server.cert.path", DEFAULT_SERVER_CERT_PATH);
        SERVER_KEY = parseFile(props, "server.key.path", DEFAULT_SERVER_KEY_PATH);
        validateFileExists(SERVER_CERT, "server.cert.path");

        CLIENT_LOCAL_PORT = parsePort(props, "client.local.port", DEFAULT_CLIENT_LOCAL_PORT);
        CLIENT_HTTP_PORT = parsePort(props, "client.http.port", DEFAULT_CLIENT_HTTP_PORT);
        CLIENT_HTTPS_PORT = parsePort(props, "client.https.port", DEFAULT_CLIENT_HTTPS_PORT);

        CLIENT_SOCKS5_ENABLED = parseBoolean(props, "client.socks5.enabled", true);
        CLIENT_HTTP_ENABLED = parseBoolean(props, "client.http.enabled", false);
        CLIENT_HTTPS_ENABLED = parseBoolean(props, "client.https.enabled", false);

        CLIENT_AUTH_TOKEN = getRequiredString(props, "client.auth.token");
        SERVER_AUTH_TOKEN_LIST = parseTokenList(props.getProperty("server.auth.token.list", ""));

        SERVER_STATS_PATH = getString(props, "server.stats.path", DEFAULT_SERVER_STATS_PATH);
        SERVER_MONITOR_PORT = parsePort(props, "server.monitor.port", DEFAULT_MONITOR_PORT);
        SERVER_MONITOR_HOST = getRequiredString(props, "server.monitor.host", DEFAULT_MONITOR_HOST);
    }

    private Config() {
    }

    private static Properties loadProperties() {
        Properties props = new Properties();
        try (InputStream input = new FileInputStream(System.getProperty("config", "proxy.properties"))) {
            props.load(input);
            return props;
        } catch (IOException ex) {
            throw new RuntimeException("读取配置文件失败，务必检查 proxy.properties 文件是否存在于程序运行目录下", ex);
        }
    }

    private static String getString(Properties props, String key, String defaultValue) {
        return props.getProperty(key, defaultValue).trim();
    }

    private static String getRequiredString(Properties props, String key) {
        return getRequiredString(props, key, null);
    }

    private static String getRequiredString(Properties props, String key, String defaultValue) {
        String value = defaultValue == null ? props.getProperty(key) : props.getProperty(key, defaultValue);
        if (value == null || value.isBlank()) {
            throw new RuntimeException("配置错误: " + key + " 不能为空");
        }
        return value.trim();
    }

    private static int parsePort(Properties props, String key, int defaultValue) {
        String value = props.getProperty(key, String.valueOf(defaultValue)).trim();
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

    private static boolean parseBoolean(Properties props, String key, boolean defaultValue) {
        String value = props.getProperty(key, String.valueOf(defaultValue)).trim();
        return Boolean.parseBoolean(value);
    }

    private static File parseFile(Properties props, String key, String defaultPath) {
        String path = props.getProperty(key, defaultPath);
        return new File(path.trim());
    }

    private static List<String> parseTokenList(String rawTokens) {
        List<String> tokens = Arrays.stream(rawTokens.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .collect(Collectors.toUnmodifiableList());
        if (tokens.isEmpty()) {
            throw new RuntimeException("配置错误: server.auth.token.list 不能为空");
        }
        return tokens;
    }

    private static void validateFileExists(File file, String keyName) {
        Objects.requireNonNull(file);
        if (!file.exists()) {
            throw new RuntimeException("配置错误: " + keyName + " 对应文件不存在: " + file.getAbsolutePath());
        }
    }
}
