package com.itjiang.core;

import com.itjiang.utils.HostFlitterUtil;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.itjiang.Config;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;

public class ClientBoot {
    private static final Logger logger = LoggerFactory.getLogger(ClientBoot.class);
    private static Channel serverChannel;
    public static void boot(String[] args) {
        HostFlitterUtil.start();
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
            serverChannel = b.bind(Config.CLIENT_LOCAL_PORT).sync().channel();
            serverChannel.closeFuture().sync();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
    public static void close() {
        serverChannel.close();
        HostFlitterUtil.close();
        logger.info("客户端已关闭");
    }
}
