package com.itjiang;

import javax.net.ssl.SSLException;

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
    public static void main(String[] args) {
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
            b.bind(Config.SERVER_PORT).sync().channel().closeFuture().sync();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
