package com.itjiang;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class BlockHostFilter {
    private static final Logger logger = LoggerFactory.getLogger(BlockHostFilter.class);
    private static final BlockHostFilter INSTANCE = new BlockHostFilter(Paths.get(Config.CLIENT_BLOCK_PATH));

    private final Path configPath;
    private final AtomicReference<Set<String>> blockedDomainsRef = new AtomicReference<>(Set.of());

    private BlockHostFilter(Path configPath) {
        this.configPath = configPath.toAbsolutePath().normalize();
        reloadRules();
        startWatcher();
    }

    public static BlockHostFilter getInstance() {
        return INSTANCE;
    }

    public boolean shouldBlock(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }

        String normalized = host.trim().toLowerCase(Locale.ROOT);
        Set<String> blockedDomains = blockedDomainsRef.get();
        if (blockedDomains.contains(normalized)) {
            return true;
        }

        String suffix = normalized;
        int dotIndex;
        while ((dotIndex = suffix.indexOf('.')) != -1) {
            suffix = suffix.substring(dotIndex + 1);
            if (!suffix.isEmpty() && blockedDomains.contains(suffix)) {
                return true;
            }
        }
        return false;
    }

    private void reloadRules() {
        if (!Files.exists(configPath)) {
            if (!blockedDomainsRef.get().isEmpty()) {
                blockedDomainsRef.set(Set.of());
                logger.info("拦截配置文件不存在，已清空规则: {}", configPath);
            }
            return;
        }

        try {
            List<String> lines = Files.readAllLines(configPath);
            Set<String> loaded = new HashSet<>();
            for (String line : lines) {
                String domain = parseDomain(line);
                if (domain != null) {
                    loaded.add(domain);
                }
            }

            Set<String> newRules = Set.copyOf(loaded);
            Set<String> oldRules = blockedDomainsRef.get();
            if (!oldRules.equals(newRules)) {
                blockedDomainsRef.set(newRules);
                logger.info("拦截规则已加载，规则数量: {}", newRules.size());
            }
        } catch (IOException e) {
            logger.error("读取拦截配置失败，保留旧规则: {}", configPath, e);
        }
    }

    private String parseDomain(String rawLine) {
        if (rawLine == null) {
            return null;
        }

        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }

        if (line.startsWith("address=/")) {
            int start = "address=/".length();
            int end = line.lastIndexOf('/');
            if (end > start) {
                line = line.substring(start, end);
            }
        }

        String normalized = line.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private void startWatcher() {
        Path parent = configPath.getParent();
        if (parent == null) {
            return;
        }

        Thread watcher = new Thread(() -> {
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                parent.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                logger.info("拦截配置监听已启动: {}", configPath);
                while (true) {
                    WatchKey key = watchService.take();
                    boolean needReload = false;
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Object context = event.context();
                        if (context instanceof Path changed
                                && changed.getFileName().equals(configPath.getFileName())) {
                            needReload = true;
                        }
                    }
                    if (!key.reset()) {
                        logger.warn("拦截配置监听失效: {}", configPath);
                        break;
                    }
                    if (needReload) {
                        reloadRules();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("拦截配置监听线程中断");
            } catch (IOException e) {
                logger.error("拦截配置监听启动失败: {}", configPath, e);
            }
        }, "block-host-watch");
        watcher.setDaemon(true);
        watcher.start();
    }
}
