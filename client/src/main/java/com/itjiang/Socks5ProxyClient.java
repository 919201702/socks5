package com.itjiang;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Socks5ProxyClient {
    private static final Logger logger = LoggerFactory.getLogger(Socks5ProxyClient.class);

    public static void start(int localPort) throws InterruptedException {
        ChannelInitializer<SocketChannel> initializer = new ChannelInitializer<>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.pipeline().addLast(new Socks5ServerEncoder(Socks5AddressEncoder.DEFAULT))
                        .addLast(new Socks5InitialRequestDecoder())
                        .addLast(Socks5InitialRequestHandler.INSTANCE);
            }
        };

        String startupMessage = String.format(
                "Socks5 代理启动成功，本地监听端口: %d, 远程服务器地址: %s:%d, 密码认证启用: %s",
                localPort,
                Config.SERVER_HOST,
                Config.SERVER_PORT,
                Config.CLIENT_SOCKS5_PASSWORD_AUTH_ENABLED
        );

        NettyProxyServer.start(localPort, initializer, logger, startupMessage);
    }
}
