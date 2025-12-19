package com.itjiang;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TargetResponseHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(TargetResponseHandler.class);
    private final ChannelHandlerContext clientCtx;

    public TargetResponseHandler(ChannelHandlerContext clientCtx) {
        this.clientCtx = clientCtx;
    }

    @Override
    public void channelRead(ChannelHandlerContext targetCtx, Object msg) {
        if (clientCtx.channel().isActive()) {
            // 收到目标服务器的数据，封装后发回给客户端
            if (msg instanceof ByteBuf buf) {
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                clientCtx.writeAndFlush(new Common.TunnelMsg(Common.TYPE_DATA, bytes));
            }
            ReferenceCountUtil.release(msg);
        } else {
            ReferenceCountUtil.release(msg);
            targetCtx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext targetCtx) {
        // 目标服务器断开连接，通知客户端断开
        if (clientCtx.channel().isActive()) {
            clientCtx.writeAndFlush(new Common.TunnelMsg(Common.TYPE_DISCONNECT, null))
                    .addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // 目标服务器可能重置连接。
        if (cause instanceof IOException && cause.getMessage() != null && cause.getMessage().contains("Connection reset")) {
            ctx.close();
        } else {
            logger.error("Exception in TargetResponseHandler", cause);
            ctx.close();
        }
    }
}
