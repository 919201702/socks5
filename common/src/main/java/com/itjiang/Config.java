package com.itjiang;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

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

    // --- 配置项 ---
    public static final int SERVER_PORT;
    public static final String SERVER_HOST;
    public static final int CLIENT_LOCAL_PORT;
    public static final String AUTH_TOKEN;
    public static final File SERVER_CERT; // server.crt
    public static final File SERVER_KEY; // server.key
    static {
        Properties props = new Properties();
        try (InputStream input = new FileInputStream("proxy.properties")) {
            props.load(input);
        } catch (IOException ex) {
            throw new RuntimeException("读取配置文件失败，务必检查 proxy.properties 文件是否存在于程序运行目录下", ex);
        }
        SERVER_PORT = Integer.parseInt(props.getProperty("server.port", String.valueOf(DEFAULT_SERVER_PORT)));
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

        CLIENT_LOCAL_PORT = Integer.parseInt(props.getProperty("client_local.port", String.valueOf(DEFAULT_LOCAL_PORT)));

        AUTH_TOKEN = props.getProperty("auth.token");
        Objects.requireNonNull(AUTH_TOKEN, "配置错误: auth.token 不能为空");
    }
}
