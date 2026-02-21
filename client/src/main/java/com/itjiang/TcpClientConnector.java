package com.itjiang;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

/**
 * 统一封装出站 TCP 连接逻辑，避免在各协议处理器中重复书写 Bootstrap 模板代码。
 */
public final class TcpClientConnector {

    private TcpClientConnector() {
    }

    public static void connect(ChannelHandlerContext sourceCtx,
                               String host,
                               int port,
                               ChannelInitializer<SocketChannel> initializer,
                               ConnectCallback callback) {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(sourceCtx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(initializer);

        bootstrap.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                callback.onFailure(future.cause());
                return;
            }
            callback.onSuccess(future.channel());
        });
    }

    public interface ConnectCallback {
        void onSuccess(Channel channel);

        void onFailure(Throwable cause);
    }
}
