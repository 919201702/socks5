package com.itjiang;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;

import javax.net.ssl.SSLException;

import static com.itjiang.Config.SERVER_HOST;
import static com.itjiang.Config.SERVER_PORT;

/**
 * 统一创建“连接远程隧道服务器”所需的 pipeline，复用 TLS 与 Tunnel 协议编解码配置。
 */
public final class TunnelChannelInitializerFactory {

    private TunnelChannelInitializerFactory() {
    }

    public static ChannelInitializer<SocketChannel> newInitializer(ChannelHandler terminalHandler) {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.pipeline().addLast(tunnelSslContext().newHandler(ch.alloc(), SERVER_HOST, SERVER_PORT));
                ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(128 * 1024 * 1024, 0, 4, 0, 4));
                ch.pipeline().addLast(new Common.TunnelMsgCodec());
                ch.pipeline().addLast(terminalHandler);
            }
        };
    }

    private static SslContext tunnelSslContext() {
        return Holder.SSL_CONTEXT;
    }

    private static final class Holder {
        private static final SslContext SSL_CONTEXT = build();

        private static SslContext build() {
            try {
                return SslContextBuilder.forClient()
                        .sslProvider(SslProvider.OPENSSL)
                        .trustManager(Config.SERVER_CERT)
                        .build();
            } catch (SSLException e) {
                throw new IllegalStateException("ssl证书配置异常", e);
            }
        }
    }
}
