package com.itjiang;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;

/**
 * 管理协议握手完成后的 pipeline 切换，集中维护“移除握手处理器 + 挂接 relay 处理器”的行为。
 */
public final class RelayPipeline {

    private RelayPipeline() {
    }

    public static void switchHttpToTunnelRelay(ChannelHandlerContext localCtx, Channel remoteChannel) {
        localCtx.executor().execute(() -> {
            removeIfPresent(localCtx, HttpProxyRequestHandler.class);
            removeIfPresent(localCtx, HttpObjectAggregator.class);
            removeIfPresent(localCtx, HttpServerCodec.class);
            localCtx.pipeline().addLast(new BrowserDataRelayHandler(remoteChannel));
        });
    }

    public static void switchHttpToTcpRelay(ChannelHandlerContext localCtx, Channel targetChannel) {
        localCtx.executor().execute(() -> {
            removeIfPresent(localCtx, HttpProxyRequestHandler.class);
            removeIfPresent(localCtx, HttpObjectAggregator.class);
            removeIfPresent(localCtx, HttpServerCodec.class);
            localCtx.pipeline().addLast(new TcpRelayHandler(targetChannel));
        });
    }

    public static void switchSocksToTunnelRelay(ChannelHandlerContext browserCtx, Channel remoteChannel) {
        browserCtx.executor().execute(() -> {
            removeIfPresent(browserCtx, Socks5CommandRequestHandler.class);
            removeIfPresent(browserCtx, Socks5CommandRequestDecoder.class);
            browserCtx.pipeline().addLast(new BrowserDataRelayHandler(remoteChannel));
        });
    }

    public static void switchSocksToTcpRelay(ChannelHandlerContext browserCtx, Channel targetChannel) {
        browserCtx.executor().execute(() -> {
            removeIfPresent(browserCtx, Socks5CommandRequestHandler.class);
            removeIfPresent(browserCtx, Socks5CommandRequestDecoder.class);
            browserCtx.pipeline().addLast(new TcpRelayHandler(targetChannel));
        });
    }

    private static void removeIfPresent(ChannelHandlerContext ctx, Class<? extends ChannelHandler> handlerClass) {
        if (ctx.pipeline().get(handlerClass) != null) {
            ctx.pipeline().remove(handlerClass);
        }
    }
}
