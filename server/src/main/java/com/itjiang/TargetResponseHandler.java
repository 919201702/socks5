package com.itjiang;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.itjiang.Monitor.TOKEN_KEY;

public class TargetResponseHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(TargetResponseHandler.class);
    private final ChannelHandlerContext clientCtx;

    public TargetResponseHandler(ChannelHandlerContext clientCtx) {
        this.clientCtx = clientCtx;
    }

    @Override
    public void channelRead(ChannelHandlerContext targetCtx, Object msg) {
        if (!clientCtx.channel().isActive()) {
            ReferenceCountUtil.release(msg);
            targetCtx.close();
            return;
        }

        if (msg instanceof ByteBuf buf) {
            String token = clientCtx.channel().attr(TOKEN_KEY).get();
            if (token != null) {
                Monitor.recordOutbound(token, buf.readableBytes());
            }
            // 手动控制引用计数，直接移交是最优处理
            clientCtx.writeAndFlush(new Common.TunnelMsg(Common.TYPE_DATA, buf));
        } else {
            logger.warn("收到意外的消息类型: {}", msg.getClass());
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext targetCtx) throws Exception {
        boolean canWrite = targetCtx.channel().isWritable();
        // 背压处理，防止targetCtx数据积压，让客户端暂停写入
        if (clientCtx.channel().isActive()) {
            clientCtx.channel().config().setAutoRead(canWrite);
        }

        super.channelWritabilityChanged(targetCtx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext targetCtx) {
        // 目标断开，通知客户端
        if (clientCtx.channel().isActive()) {
            clientCtx.writeAndFlush(new Common.TunnelMsg(Common.TYPE_DISCONNECT))
                    .addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof IOException && cause.getMessage() != null && cause.getMessage().contains("Connection reset")) {
            logger.debug("Target connection reset");
        } else {
            logger.error("Exception in TargetResponseHandler", cause);
        }
        ctx.close();
    }
}