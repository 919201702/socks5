package com.itjiang;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public final class DirectAllowFilter {
    private static final Logger logger = LoggerFactory.getLogger(DirectAllowFilter.class);
    private static final DirectAllowFilter INSTANCE = new DirectAllowFilter(Paths.get(Config.CLIENT_DIRECT_ALLOW_PATH));

    private final Path configPath;
    private final AtomicReference<List<Rule>> rulesRef = new AtomicReference<>(List.of());

    private DirectAllowFilter(Path configPath) {
        this.configPath = configPath.toAbsolutePath().normalize();
        reloadRules();
        startWatcher();
    }

    public static DirectAllowFilter getInstance() {
        return INSTANCE;
    }

    public boolean shouldDirect(String hostOrIp) {
        if (hostOrIp == null || hostOrIp.isBlank()) {
            return false;
        }
        String value = hostOrIp.trim().toLowerCase(Locale.ROOT);
        for (Rule rule : rulesRef.get()) {
            if (rule.matches(value)) {
                return true;
            }
        }
        return false;
    }

    private void reloadRules() {
        if (!Files.exists(configPath)) {
            rulesRef.set(List.of());
            logger.info("直连配置文件不存在，已清空规则: {}", configPath);
            return;
        }

        try {
            List<String> lines = Files.readAllLines(configPath);
            List<Rule> loaded = new ArrayList<>();
            for (String line : lines) {
                Rule rule = parseRule(line);
                if (rule != null) {
                    loaded.add(rule);
                }
            }
            rulesRef.set(List.copyOf(loaded));
            logger.info("直连配置已加载，规则数量: {}", loaded.size());
        } catch (IOException e) {
            logger.error("读取直连配置失败，保留旧规则: {}", configPath, e);
        }
    }

    private Rule parseRule(String rawLine) {
        if (rawLine == null) {
            return null;
        }
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }

        String normalized = line.toLowerCase(Locale.ROOT);
        boolean startsWithStar = normalized.startsWith("*");
        boolean endsWithStar = normalized.endsWith("*");

        if (startsWithStar && endsWithStar && normalized.length() > 2) {
            return new ContainsRule(normalized.substring(1, normalized.length() - 1));
        }
        if (startsWithStar && normalized.length() > 1) {
            return new SuffixRule(normalized.substring(1));
        }
        if (endsWithStar && normalized.length() > 1) {
            return new PrefixRule(normalized.substring(0, normalized.length() - 1));
        }
        return new ExactRule(normalized);
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
                logger.info("直连配置监听已启动: {}", configPath);
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
                        logger.warn("直连配置监听失效: {}", configPath);
                        break;
                    }
                    if (needReload) {
                        reloadRules();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("直连配置监听线程中断");
            } catch (IOException e) {
                logger.error("直连配置监听启动失败: {}", configPath, e);
            }
        }, "direct-allow-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private interface Rule {
        boolean matches(String value);
    }

    private record ExactRule(String value) implements Rule {
        @Override
        public boolean matches(String target) {
            return value.equals(target);
        }
    }

    private record PrefixRule(String value) implements Rule {
        @Override
        public boolean matches(String target) {
            return target.startsWith(value);
        }
    }

    private record SuffixRule(String value) implements Rule {
        @Override
        public boolean matches(String target) {
            return target.endsWith(value);
        }
    }

    private record ContainsRule(String value) implements Rule {
        @Override
        public boolean matches(String target) {
            return target.contains(value);
        }
    }
}
