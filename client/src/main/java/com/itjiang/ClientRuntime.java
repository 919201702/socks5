package com.itjiang;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * 客户端服务编排器：负责根据配置启动多个本地代理服务，并在任一服务异常时快速失败。
 */
public class ClientRuntime {
    private static final Logger logger = LoggerFactory.getLogger(ClientRuntime.class);

    private final List<ServiceSpec> services;

    public ClientRuntime(List<ServiceSpec> services) {
        this.services = services;
    }

    public void start() throws InterruptedException {
        List<Thread> threads = new ArrayList<>();
        for (ServiceSpec service : services) {
            if (!service.enabled()) {
                continue;
            }
            threads.add(startServiceThread(service));
        }

        if (threads.isEmpty()) {
            throw new IllegalStateException("配置错误: client.socks5.enabled/client.http.enabled/client.https.enabled 至少开启一个");
        }

        logger.info("客户端启动配置: socks5={}, http={}, https={}",
                Config.CLIENT_SOCKS5_ENABLED, Config.CLIENT_HTTP_ENABLED, Config.CLIENT_HTTPS_ENABLED);

        // 使用 latch 保持主线程存活，避免 JVM 提前退出。
        new CountDownLatch(1).await();
    }

    private Thread startServiceThread(ServiceSpec service) {
        Thread t = new Thread(() -> {
            try {
                service.starter().start();
            } catch (Throwable e) {
                logger.error("{} 代理服务启动失败", service.name(), e);
                System.exit(1);
            }
        }, "client-" + service.name() + "-service");

        t.setDaemon(false);
        t.start();
        return t;
    }

    public record ServiceSpec(String name, boolean enabled, ServiceStarter starter) {
    }

    @FunctionalInterface
    public interface ServiceStarter {
        void start() throws Exception;
    }
}
