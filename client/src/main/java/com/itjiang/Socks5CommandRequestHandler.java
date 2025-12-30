package com.itjiang;

import static com.itjiang.Config.*;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;

import java.io.IOException;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;

@ChannelHandler.Sharable
public class Socks5CommandRequestHandler extends SimpleChannelInboundHandler<Socks5CommandRequest> {
    private static final Logger logger = LoggerFactory.getLogger(Socks5CommandRequestHandler.class);
    public static final Socks5CommandRequestHandler INSTANCE = new Socks5CommandRequestHandler();

    @Override
    protected void channelRead0(ChannelHandlerContext browserCtx, Socks5CommandRequest request) {
        if (request.type() == Socks5CommandType.CONNECT) {
            connectToRemoteServer(browserCtx, request);
        } else {
            browserCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, request.dstAddrType()));
            browserCtx.close();
        }
    }

    private void connectToRemoteServer(ChannelHandlerContext browserCtx, Socks5CommandRequest request) {
        SslContext sslCtx;
        try {
            sslCtx = SslContextBuilder.forClient()
                    .sslProvider(SslProvider.OPENSSL)
                    // 只信任该server.crt
                    .trustManager(Config.SERVER_CERT)
                    .build();
        } catch (SSLException e) {
            throw new RuntimeException("ssl证书配置异常", e);
        }
        Bootstrap b = new Bootstrap();
        b.group(browserCtx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // 连接远程服务器的 pipeline
                        ch.pipeline().addLast(sslCtx.newHandler(ch.alloc(), SERVER_HOST, SERVER_PORT));
                        ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(128 * 1024 * 1024, 0, 4, 0, 4));
                        ch.pipeline().addLast(new Common.TunnelMsgDecoder());
                        ch.pipeline().addLast(new Common.TunnelMsgEncoder());
                        ch.pipeline().addLast(new RemoteConnectionHandler(browserCtx, request));
                    }
                });

        b.connect(SERVER_HOST, SERVER_PORT).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                logger.error("连接远程服务器失败");
                // 连接远程服务器失败，直接告诉浏览器
                browserCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, request.dstAddrType()));
                browserCtx.close();
            }
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // 命令处理阶段，浏览器也可能重置连接
        if (cause instanceof IOException && cause.getMessage() != null && cause.getMessage().contains("Connection reset")) {
            ctx.close();
        } else {
            logger.error("Exception in Socks5CommandRequestHandler", cause);
            ctx.close();
        }
    }
}
