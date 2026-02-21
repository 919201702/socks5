package com.itjiang;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;

/**
 * 统一封装 Netty TCP 服务端启动流程，减少不同代理协议之间的样板代码。
 */
public final class NettyProxyServer {

    private NettyProxyServer() {
    }

    public static void start(int localPort, ChannelInitializer<SocketChannel> initializer, Logger logger, String startupMessage)
            throws InterruptedException {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(initializer);

            logger.info(startupMessage);
            bootstrap.bind(localPort).sync().channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
