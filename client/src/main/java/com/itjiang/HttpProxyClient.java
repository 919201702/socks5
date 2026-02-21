package com.itjiang;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpProxyClient {
    private static final Logger logger = LoggerFactory.getLogger(HttpProxyClient.class);

    public static void start(boolean isHttps, int localPort) throws InterruptedException {
        ChannelInitializer<SocketChannel> initializer = new ChannelInitializer<>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.pipeline().addLast(new HttpServerCodec())
                        .addLast(new HttpObjectAggregator(16 * 1024 * 1024))
                        .addLast(new HttpProxyRequestHandler(isHttps));
            }
        };

        String startupMessage = String.format("%s代理客户端启动成功，本地监听端口: %d, 远程服务器地址: %s:%d",
                isHttps ? "HTTPS " : "HTTP ",
                localPort,
                Config.SERVER_HOST,
                Config.SERVER_PORT);

        NettyProxyServer.start(localPort, initializer, logger, startupMessage);
    }
}
