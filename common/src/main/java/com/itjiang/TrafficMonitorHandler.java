package com.itjiang;

import java.util.Objects;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * 基于ChannelDuplexHandler的流量监控处理器（单连接+全局汇总）
 */
public class TrafficMonitorHandler extends ChannelDuplexHandler {
    private final String clientToken;
    public TrafficMonitorHandler(String clientToken) {
        Objects.requireNonNull(clientToken);
        this.clientToken = clientToken;
    }

    // ========== 统计入站读流量 ==========
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Common.TunnelMsg tunnelMsg) {
            if (tunnelMsg.getType() == Common.TYPE_DATA) {
                int readBytes = tunnelMsg.getData().readableBytes();
                Monitor.recordInbound(clientToken, readBytes);
            }
        }
        super.channelRead(ctx, msg);
    }

    // ========== 统计出站写流量 ==========
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof Common.TunnelMsg tunnelMsg) {
            if (tunnelMsg.getType() == Common.TYPE_DATA) {
                int writeBytes = tunnelMsg.getData().readableBytes();
                Monitor.recordOutbound(clientToken, writeBytes);
            }
        }
        super.write(ctx, msg, promise);
    }
}