package com.itjiang;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpProxyClient {
    private static final Logger logger = LoggerFactory.getLogger(HttpProxyClient.class);

    public static void main(String[] args) throws InterruptedException {
        start(false, Config.CLIENT_HTTP_PORT);
    }

    public static void start(boolean connectOnly, int localPort) throws InterruptedException {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(16 * 1024 * 1024))
                                    .addLast(new HttpProxyRequestHandler(connectOnly));
                        }
                    });

            logger.info("{}代理客户端启动成功，本地监听端口: {}, 远程服务器地址: {}:{}",
                    connectOnly ? "HTTPS " : "HTTP "
                    , localPort
                    , Config.SERVER_HOST
                    , Config.SERVER_PORT);
            b.bind(localPort).sync().channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
