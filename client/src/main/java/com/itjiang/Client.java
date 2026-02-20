package com.itjiang;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class Client {
    private static final Logger logger = LoggerFactory.getLogger(Client.class);

    public static void main(String[] args) throws Exception {
        runByConfig();
    }


    private static void runByConfig() throws InterruptedException {
        List<Thread> threads = new ArrayList<>();

        if (Config.CLIENT_SOCKS5_ENABLED) {
            threads.add(startServiceThread("socks5", () -> Socks5ProxyClient.start(Config.CLIENT_SOCKS5_PORT)));
        }
        if (Config.CLIENT_HTTP_ENABLED) {
            threads.add(startServiceThread("http", () -> HttpProxyClient.start(false, Config.CLIENT_HTTP_PORT)));
        }
        if (Config.CLIENT_HTTPS_ENABLED) {
            threads.add(startServiceThread("https", () -> HttpProxyClient.start(true, Config.CLIENT_HTTPS_PORT)));
        }

        if (threads.isEmpty()) {
            throw new RuntimeException("配置错误: client.socks5.enabled/client.http.enabled/client.https.enabled 至少开启一个");
        }

        logger.info("客户端启动配置: socks5={}, http={}, https={}",
                Config.CLIENT_SOCKS5_ENABLED, Config.CLIENT_HTTP_ENABLED, Config.CLIENT_HTTPS_ENABLED);

        CountDownLatch latch = new CountDownLatch(1);
        latch.await();
    }

    private static Thread startServiceThread(String name, Service service) {
        Thread t = new Thread(() -> {
            try {
                service.start();
            } catch (Throwable e) {
                logger.error("{} 代理服务启动失败", name, e);
                System.exit(1);
            }
        }, "client-" + name + "-service");
        t.setDaemon(false);
        t.start();
        return t;
    }

    @FunctionalInterface
    private interface Service {
        void start() throws Exception;
    }
}
