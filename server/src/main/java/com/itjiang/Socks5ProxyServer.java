package com.itjiang;

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

    public static void main(String[] args) throws InterruptedException {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(128 * 1024 * 1024, 0, 4, 0, 4))
                                    .addLast(new Common.TunnelEncoder())
                                    .addLast(new Common.TunnelDecoder())
                                    .addLast(new RemoteTunnelHandler());
                        }
                    });

            logger.info("代理服务器启动，监听端口: {}", Config.SERVER_PORT);
            b.bind(Config.SERVER_PORT).sync().channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
