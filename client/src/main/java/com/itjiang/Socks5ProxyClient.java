package com.itjiang;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;

public class Socks5ProxyClient {
    private static final Logger logger = LoggerFactory.getLogger(Socks5ProxyClient.class);

    public static void main(String[] args) throws InterruptedException {
        // Boss Group处理连接请求，Worker Group处理I/O
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new Socks5ServerEncoder(Socks5AddressEncoder.DEFAULT))
                                    .addLast(new Socks5InitialRequestDecoder())
                                    .addLast(Socks5InitialRequestHandler.INSTANCE);
                        }
                    });

            logger.info("客户端启动成功，本地Socks5监听端口: {}, 远程服务器地址: {}:{}"
                    , Config.CLIENT_LOCAL_PORT
                    , Config.SERVER_HOST
                    , Config.SERVER_PORT);
            b.bind(Config.CLIENT_LOCAL_PORT).sync().channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
