package com.itjiang;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemoteDataRelayHandler extends SimpleChannelInboundHandler<Common.TunnelMsg> {
    private static final Logger logger = LoggerFactory.getLogger(RemoteDataRelayHandler.class);

    private final Channel browserChannel;
    public RemoteDataRelayHandler(Channel browserChannel) {
        this.browserChannel = browserChannel;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Common.TunnelMsg msg) {
        if (msg.getType() == Common.TYPE_DATA) {
            if (browserChannel.isActive()) {
                browserChannel.writeAndFlush(msg.getData().retainedDuplicate());
            }
        } else if (msg.getType() == Common.TYPE_DISCONNECT) {
            if (browserChannel.isActive()) {
                browserChannel.close();
            }
            ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (browserChannel.isActive()) {
            browserChannel.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // 远程服务器或网络问题可能导致连接重置。
        if (cause instanceof IOException && cause.getMessage() != null && cause.getMessage().contains("Connection reset")) {
            ctx.close();
        } else {
            logger.error("Exception in RemoteDataRelayHandler", cause);
            ctx.close();
        }
    }
}
