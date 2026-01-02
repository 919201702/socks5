package com.itjiang.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HostFlitterUtil {
    private static final Logger logger = LoggerFactory.getLogger(HostFlitterUtil.class);

    private static final String CONFIG_FILE_NAME = "block.conf";
    private static final Path CONFIG_DIR = Paths.get(".");
    private static volatile Set<String> blockedDomains = new HashSet<>();
    private static Thread watcherThread;
    private static volatile boolean running = true;
    public static void start()  {
        loadConfig();
        watcherThread = Thread.ofVirtual().name("config-watcher").start(HostFlitterUtil::startWatching);
        running = true;
    }

    public static void close() {
        running = false;
        if (watcherThread != null) {
            watcherThread.interrupt();
        }
    }

    /**
     * 判断域名是否应该被过滤
     * 支持泛域名匹配：如果 block 了 example.com，那么 ad.example.com 也会返回 true
     */
    public static boolean blockHost(String host) {
        if (host == null || host.isEmpty() || !watcherThread.isAlive()) {
            return false;
        }

        Set<String> currentBlockList = blockedDomains;

        // 匹配完整域名
        if (currentBlockList.contains(host)) {
            return true;
        }

        // 泛域名匹配，逐级向上查找
        String nextDomain = host;
        int dotIndex;
        while ((dotIndex = nextDomain.indexOf('.')) != -1) {
            nextDomain = nextDomain.substring(dotIndex + 1);
            if (!nextDomain.isEmpty() && currentBlockList.contains(nextDomain)) {
                return true;
            }
        }

        return false;
    }

    private static void startWatching() {
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            CONFIG_DIR.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
            logger.info("开始监听配置文件变动: {}", CONFIG_DIR.toAbsolutePath());

            while (running) {
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (InterruptedException x) {
                    return;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                    Path filename = (Path) event.context();
                    if (filename.toString().equals(CONFIG_FILE_NAME)) {
                        logger.info("检测到规则文件变更，准备重新加载...");
                        // 防抖
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                        loadConfig();
                    }
                }
                if (!key.reset()) break;
            }
        } catch (IOException e) {
            logger.error("监听配置文件失败", e);
        }
    }

    private static void loadConfig() {
        Path configPath = CONFIG_DIR.resolve(CONFIG_FILE_NAME);
        if (!Files.exists(configPath)) {
            logger.error("配置文件不存在: " + configPath);
            return;
        }

        try {
            List<String> lines = Files.readAllLines(configPath);
            Set<String> newSet = new HashSet<>(lines.size());

            for (String line : lines) {
                String domain = parseDomain(line);
                if (domain != null && !domain.isEmpty()) {
                    newSet.add(domain);
                }
            }

            blockedDomains = newSet;
            logger.info("配置已更新至内存，当前加载规则数: {}", newSet.size());

        } catch (IOException e) {
            logger.error("读取配置文件失败", e);
        }
    }

    /**
     * 解析 dnsmasq 格式或纯域名格式
     * 输入: address=/000dn.com/  -> 输出: 000dn.com
     * 输入: # 这是一个注释       -> 输出: null
     */
    private static String parseDomain(String line) {
        String trimLine = line.trim();
        if (trimLine.isEmpty() || trimLine.startsWith("#")) {
            return null;
        }
        // 处理 dnsmasq 格式: address=/domain/
        if (trimLine.startsWith("address=/")) {
            int start = "address=/".length();
            int end = trimLine.lastIndexOf('/');
            if (end > start) {
                return trimLine.substring(start, end);
            }
        }
        // 纯域名，直接返回
         return trimLine;
    }
}