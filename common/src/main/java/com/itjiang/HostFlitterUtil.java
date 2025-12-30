package com.itjiang;

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

    // 使用 volatile 保证可见性，使用 Set 提高查询效率 (O(1))
    // 初始化为空 Set 防止空指针
    private static volatile Set<String> blockedDomains = new HashSet<>();

    /**
     * 启动监听
     */
    static {
        start();
    }
    public static void start() {
        // 首次加载
        loadConfig();
        // 启动虚拟线程监听
        Thread.ofVirtual().name("config-watcher").start(HostFlitterUtil::startWatching);
    }

    /**
     * 判断域名是否应该被过滤
     * 支持泛域名匹配：如果 block 了 example.com，那么 ad.example.com 也会返回 true
     */
    public static boolean blockHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }

        // 获取当前的配置引用（因为 blockedDomains 是 volatile 的，读取是原子的）
        Set<String> currentBlockList = blockedDomains;

        // 1. 直接匹配完整域名
        if (currentBlockList.contains(host)) {
            return true;
        }

        // 2. 泛域名匹配 (逐级向上查找)
        // 例如：host 是 "a.b.baidu.com"
        // 检查 "b.baidu.com" -> 检查 "baidu.com" -> 检查 "com"
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

            while (true) {
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
                // 简单的解析逻辑
                String domain = parseDomain(line);
                if (domain != null && !domain.isEmpty()) {
                    newSet.add(domain);
                }
            }

            // 原子替换：将引用指向新的 Set
            blockedDomains = newSet;
            logger.info("配置已更新，当前加载规则数: {}", newSet.size());

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

        // 如果文件里混杂了纯域名，也可以直接返回
        // return trimLine;

        return null;
    }
}