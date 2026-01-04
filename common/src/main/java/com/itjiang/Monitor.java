package com.itjiang;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Monitor {
    private static final Logger logger = LoggerFactory.getLogger(Monitor.class);
    // 单连接统计：读字节数、写字节数
    private static final Map<String, AtomicLong> inBoundStatsMap = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> outBoundStatsMap = new ConcurrentHashMap<>();

    // 全局流量汇总（所有连接累加）
    private static final AtomicLong GLOBAL_TOTAL_READ_BYTES = new AtomicLong(0);
    private static final AtomicLong GLOBAL_TOTAL_WRITE_BYTES = new AtomicLong(0);

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final String GLOBAL_TOKEN = "global";

    public static final AttributeKey<String> TOKEN_KEY = AttributeKey.valueOf("client_token");
    // --- 异步记录流量的线程池 ---
    private static final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            2, // cpu密集型
            2,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100000),
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "monitor-pool-thread-" + threadNumber.getAndIncrement());
                    if (t.isDaemon()) {
                        t.setDaemon(false);
                    }
                    if (t.getPriority() != Thread.NORM_PRIORITY) {
                        t.setPriority(Thread.NORM_PRIORITY);
                    }
                    return t;
                }
            },
            // 拒绝策略：调用者执行
            new ThreadPoolExecutor.CallerRunsPolicy()
    );
    private Monitor() { }
    public static void recordInbound(String token, long bytes) {
        executor.execute(() -> {
            inBoundStatsMap.compute(token, (s, trafficStats) -> Optional.ofNullable(trafficStats).map(i -> {
                i.addAndGet(bytes);
                return i;
            }).orElse(new AtomicLong(bytes)));
            GLOBAL_TOTAL_READ_BYTES.addAndGet(bytes);
        });
    }

    public static void recordOutbound(String token, long bytes) {
        executor.execute(() -> {
            outBoundStatsMap.compute(token, (s, trafficStats) -> Optional.ofNullable(trafficStats).map(i -> {
                i.addAndGet(bytes);
                return i;
            }).orElse(new AtomicLong(bytes)));
            GLOBAL_TOTAL_WRITE_BYTES.addAndGet(bytes);
        });
    }

    /**
     * 获取所有监控数据的快照，用于序列化
     * @return 包含全局和各token流量的Map
     */
    public static Detail getStats() {
        Statistics global = new Statistics(getAllInboundDetail(), getAllOutboundDetail(), GLOBAL_TOKEN);
        List<Statistics> tokens = Stream.concat(inBoundStatsMap.keySet().stream(), outBoundStatsMap.keySet().stream())
                .distinct()
                .map(token -> new Statistics(getInboundDetail(token), getOutboundDetail(token), token)).toList();
        return new Detail(global, tokens);
    }
    public static void reloadStats(String statsPath) {
        try {
            Path path = Path.of(statsPath);
            if (!path.toFile().exists()) {
                Files.createFile(path);
            }
            String stats = Files.readString(path);
            List<StatsDetail> detailList = GSON.fromJson(stats, TypeToken.getParameterized(List.class, StatsDetail.class).getType());
            if (detailList != null) {
                for (StatsDetail detail : detailList) {
                    String token = detail.token;
                    if (GLOBAL_TOKEN.equals(token)) {
                        GLOBAL_TOTAL_READ_BYTES.set(detail.readBytes());
                        GLOBAL_TOTAL_WRITE_BYTES.set(detail.writeBytes());
                    } else {
                        inBoundStatsMap.put(token, new AtomicLong(detail.readBytes()));
                        outBoundStatsMap.put(token, new AtomicLong(detail.writeBytes()));
                    }
                }
            }
            logger.info("成功加载监控历史文件: {}", statsPath);
        } catch (IOException e) {
            logger.error("读取监控历史文件失败: {}", statsPath, e);
        }
    }
    private static void persistent(String statsPath) {
        try {
            Path path = Path.of(statsPath);
            if (!path.toFile().exists()) {
                Files.createFile(path);
            }
            List<String> tokenList = Stream.concat(inBoundStatsMap.keySet().stream(), outBoundStatsMap.keySet().stream()).distinct().toList();
            List<StatsDetail> statsDetailList = tokenList.stream().map(token -> {
                long in = Optional.ofNullable(inBoundStatsMap.get(token)).orElse(new AtomicLong(0)).get();
                long out = Optional.ofNullable(outBoundStatsMap.get(token)).orElse(new AtomicLong(0)).get();
                return new StatsDetail(in, out, token);
            }).collect(Collectors.toList());
            statsDetailList.add(new StatsDetail(GLOBAL_TOTAL_READ_BYTES.get(), GLOBAL_TOTAL_WRITE_BYTES.get(), GLOBAL_TOKEN));
            Files.writeString(path, GSON.toJson(statsDetailList));
            logger.info("成功持久化监控历史文件: {}", statsPath);
        } catch (IOException e) {
            logger.error("持久化监控历史文件失败: {}", statsPath, e);
        }
    }

    public static void shutdown(String statsPath) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        persistent(statsPath);
    }

    public static String getAllInboundDetail() {
        return formatBytes(GLOBAL_TOTAL_READ_BYTES.get());
    }

    public static String getAllOutboundDetail() {
        return formatBytes(GLOBAL_TOTAL_WRITE_BYTES.get());
    }

    public static String getInboundDetail(String token) {
        return Optional.ofNullable(inBoundStatsMap.get(token))
                .map(AtomicLong::get)
                .map(Monitor::formatBytes)
                .orElse(null);
    }

    public static String getOutboundDetail(String token) {
        return Optional.ofNullable(outBoundStatsMap.get(token))
                .map(AtomicLong::get)
                .map(Monitor::formatBytes)
                .orElse(null);
    }

    private static String formatBytes(long bytes) {
        long kbTotal = bytes / 1024;
        if (kbTotal == 0) return "0 kb";
        long tb = kbTotal / (1024L * 1024 * 1024);
        long gb = (kbTotal / (1024 * 1024)) % 1024;
        long mb = (kbTotal / 1024) % 1024;
        long kb = kbTotal % 1024;
        StringBuilder sb = new StringBuilder();
        if (tb > 0) sb.append(tb).append(" tb ");
        if (gb > 0) sb.append(gb).append(" gb ");
        if (mb > 0) sb.append(mb).append(" mb ");
        if (kb > 0) sb.append(kb).append(" kb");
        return sb.toString().trim();
    }

    public record Statistics(String inboundBytes, String outboundBytes, String token) { }
    public record Detail(Statistics global, List<Statistics> tokens) { }

    private record StatsDetail(long readBytes, long writeBytes, String token) { }
}
