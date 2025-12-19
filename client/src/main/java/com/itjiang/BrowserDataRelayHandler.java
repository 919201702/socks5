package com.itjiang;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 4a. 将浏览器数据 -> 加密 -> 发送到远程服务器
public class BrowserDataRelayHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(BrowserDataRelayHandler.class);
    private final Channel remoteChannel;

    public BrowserDataRelayHandler(Channel remoteChannel) {
        this.remoteChannel = remoteChannel;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (remoteChannel.isActive()) {
            if (msg instanceof ByteBuf buf) {
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                remoteChannel.writeAndFlush(new Common.TunnelMsg(Common.TYPE_DATA, bytes));
            }
            ReferenceCountUtil.release(msg);
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (remoteChannel.isActive()) {
            remoteChannel.writeAndFlush(new Common.TunnelMsg(Common.TYPE_DISCONNECT, null))
                    .addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // 当浏览器或客户端强制关闭连接时，会抛出 "Connection reset" 异常。
        // 这是一种正常情况，我们只需关闭连接，无需打印堆栈信息。
        if (cause instanceof IOException && cause.getMessage() != null && cause.getMessage().contains("Connection reset")) {
            ctx.close();
        } else {
            logger.error("Exception in BrowserDataRelayHandler", cause);
            ctx.close();
        }
    }
}
