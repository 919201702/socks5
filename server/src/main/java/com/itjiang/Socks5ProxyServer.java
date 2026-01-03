package com.itjiang;

import javax.net.ssl.SSLException;
import java.util.concurrent.atomic.AtomicBoolean; // 引入原子类

import com.itjiang.web.MonitorDashboard;
import io.netty.channel.Channel;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

public class Socks5ProxyServer {

    private static final Logger logger = LoggerFactory.getLogger(Socks5ProxyServer.class);
    private static final AtomicBoolean isCleaned = new AtomicBoolean(false);
    public static void main(String[] args) {
        String statsPath = "./stats.json";
        MonitorDashboard.start(statsPath);
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        final SslContext sslCtx;
        try {
            sslCtx = SslContextBuilder.forServer(Config.SERVER_CERT, Config.SERVER_KEY)
                    .sslProvider(SslProvider.OPENSSL)
                    .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                    .applicationProtocolConfig(ApplicationProtocolConfig.DISABLED)
                    .build();
        } catch (SSLException e) {
            throw new RuntimeException("ssl证书配置异常", e);
        }
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(sslCtx.newHandler(ch.alloc()))
                                    .addLast(new LengthFieldBasedFrameDecoder(128 * 1024 * 1024, 0, 4, 0, 4))
                                    .addLast(new Common.TunnelMsgEncoder())
                                    .addLast(new Common.TunnelMsgDecoder())
                                    .addLast(new RemoteTunnelHandler());
                        }
                    });

            logger.info("代理服务器启动，监听端口: {}", Config.SERVER_PORT);
            Channel serverChannel = b.bind(Config.SERVER_PORT).sync().channel();
            // 注册关闭钩子
            addShutdownHook(serverChannel, bossGroup, workerGroup, statsPath);
            serverChannel.closeFuture().sync();
        } catch (InterruptedException e) {
            logger.error("主线程被中断", e);
            Thread.currentThread().interrupt();
        } finally {
            cleanResources(null, bossGroup, workerGroup, statsPath);
        }
    }

    /**
     * 注册 JVM 关闭钩子
     */
    private static void addShutdownHook(Channel channel, EventLoopGroup bossGroup, EventLoopGroup workerGroup, String statsPath) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("接收到退出信号(kill -15)，正在执行 Hook 清理...");
            cleanResources(channel, bossGroup, workerGroup, statsPath);
            logger.info("Hook 清理完成，退出。");
        }));
    }

    /**
     * 【核心】统一的资源释放方法，保证只执行一次
     */
    private static void cleanResources(Channel channel, EventLoopGroup bossGroup, EventLoopGroup workerGroup, String statsPath) {
        if (!isCleaned.compareAndSet(false, true)) {
            return;
        }

        logger.info("开始释放资源...");

        // 关闭 Channel (如果是 Hook 触发的，这一步其实是为了保险)
        if (channel != null && channel.isOpen()) {
            channel.close();
        }

        // 关闭线程组
        try {
            if (!bossGroup.isShutdown()) bossGroup.shutdownGracefully().sync();
            if (!workerGroup.isShutdown()) workerGroup.shutdownGracefully().sync();
        } catch (InterruptedException e) {
            logger.error("Netty 关闭被中断", e);
        }

        // 保存监控数据 (只会被执行一次，防止文件写入冲突)
        Monitor.shutdown(statsPath);
        logger.info("资源释放完毕。");
    }
}