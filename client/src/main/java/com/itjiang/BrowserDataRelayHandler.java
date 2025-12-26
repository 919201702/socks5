package com.itjiang;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 将浏览器数据 -> 加密 -> 发送到远程服务器
public class BrowserDataRelayHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(BrowserDataRelayHandler.class);
    private final Channel remoteChannel;

    public BrowserDataRelayHandler(Channel remoteChannel) {
        this.remoteChannel = remoteChannel;
    }

    @Override
    public void channelRead(ChannelHandlerContext browserCtx, Object msg) {
        if (remoteChannel.isActive()) {
            if (msg instanceof ByteBuf buf) {
                remoteChannel.writeAndFlush(new Common.TunnelMsg(Common.TYPE_DATA, buf));
            }
            ReferenceCountUtil.release(msg);
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext browserCtx) {
        if (remoteChannel.isActive()) {
            remoteChannel.writeAndFlush(new Common.TunnelMsg(Common.TYPE_DISCONNECT, (String) null))
                    .addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext browserCtx, Throwable cause) {
        if (cause instanceof IOException && cause.getMessage() != null && cause.getMessage().contains("Connection reset")) {
            browserCtx.close();
        } else {
            logger.error("Exception in BrowserDataRelayHandler", cause);
            browserCtx.close();
        }
    }
}
