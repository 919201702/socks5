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
        MonitorDashboard.start();
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
            addShutdownHook(serverChannel, bossGroup, workerGroup);
            serverChannel.closeFuture().sync();
        } catch (InterruptedException e) {
            logger.error("主线程被中断", e);
            Thread.currentThread().interrupt();
        } finally {
            cleanResources(null, bossGroup, workerGroup);
        }
    }

    private static void addShutdownHook(Channel channel, EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("接收到退出信号(kill -15)，正在执行 Hook 清理...");
            cleanResources(channel, bossGroup, workerGroup);
            logger.info("Hook 清理完成，退出。");
        }));
    }

    private static void cleanResources(Channel channel, EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
        if (!isCleaned.compareAndSet(false, true)) {
            return;
        }
        logger.info("开始释放资源...");
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
        try {
            if (!bossGroup.isShutdown()) bossGroup.shutdownGracefully().sync();
            if (!workerGroup.isShutdown()) workerGroup.shutdownGracefully().sync();
        } catch (InterruptedException e) {
            logger.error("Netty 关闭被中断", e);
        }
        // 关闭监控资源
        MonitorDashboard.close();
        logger.info("资源释放完毕。");
    }
}