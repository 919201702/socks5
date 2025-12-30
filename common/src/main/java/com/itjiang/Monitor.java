package com.itjiang;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import io.netty.util.AttributeKey;

public final class Monitor {
    // 单连接统计：读字节数、写字节数
    private static final Map<String, AtomicLong> inBoundStatsMap = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> outBoundStatsMap = new ConcurrentHashMap<>();

    // 全局流量汇总（所有连接累加）
    private static final AtomicLong GLOBAL_TOTAL_READ_BYTES = new AtomicLong(0);
    private static final AtomicLong GLOBAL_TOTAL_WRITE_BYTES = new AtomicLong(0);

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
            // 修正笔误：这里应该是累加 READ_BYTES
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
    public static Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();

        // 全局统计
        Map<String, String> globalStats = new HashMap<>();
        globalStats.put("totalInboundBytes", getAllInboundDetail());
        globalStats.put("totalOutboundBytes", getAllOutboundDetail());
        stats.put("global", globalStats);

        // Token维度统计
        Map<String, Map<String, String>> tokenStats = new HashMap<>();
        Stream.concat(inBoundStatsMap.keySet().stream(), outBoundStatsMap.keySet().stream())
              .distinct()
              .forEach(token -> {
                  Map<String, String> singleTokenStat = new HashMap<>();
                  singleTokenStat.put("inboundBytes", getInboundDetail(token));
                  singleTokenStat.put("outboundBytes", getOutboundDetail(token));
                  tokenStats.put(token, singleTokenStat);
              });
        stats.put("tokens", tokenStats);

        return stats;
    }


    public static void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
        if (bytes < 1024) {
            return bytes + " byte";
        } else if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " kb";
        } else if (bytes < 1024 * 1024 * 1024) {
            return (bytes / (1024 * 1024)) + " mb";
        } else if (bytes < 1024L * 1024 * 1024 * 1024) {
            return (bytes / (1024 * 1024 * 1024)) + " gb";
        } else {
            return (bytes / (1024L * 1024 * 1024 * 1024)) + " tb";
        }
    }
}
