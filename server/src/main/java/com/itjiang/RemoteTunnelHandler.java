package com.itjiang;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;

public class RemoteTunnelHandler extends SimpleChannelInboundHandler<Common.TunnelMsg> {

    private static final Logger logger = LoggerFactory.getLogger(RemoteTunnelHandler.class);

    private Channel targetChannel;
    private boolean authenticated = false;

    @Override
    protected void channelRead0(ChannelHandlerContext clientCtx, Common.TunnelMsg msg) {
        switch (msg.getType()) {
            case Common.TYPE_AUTH -> handleAuth(clientCtx, msg);
            case Common.TYPE_CONNECT -> handleConnect(clientCtx, msg);
            case Common.TYPE_DATA -> handleData(clientCtx, msg);
            case Common.TYPE_DISCONNECT -> handleDisconnect(clientCtx);
            default -> ReferenceCountUtil.release(msg);
        }
    }

    private void handleAuth(ChannelHandlerContext clientCtx, Common.TunnelMsg msg) {
        String token = Unpooled.wrappedBuffer(msg.getData()).toString(StandardCharsets.UTF_8);
        if (Config.AUTH_TOKEN.equals(token)) {
            authenticated = true;
            logger.info("新的客户端连接: {}", clientCtx.channel().remoteAddress());
        } else {
            logger.warn("非法客户端连接: {}", clientCtx.channel().remoteAddress());
            Common.TunnelMsg toMsg = new Common.TunnelMsg(Common.TYPE_CONNECT_FAIL, "token验证失败");
            clientCtx.writeAndFlush(toMsg)
                    .addListener(ChannelFutureListener.CLOSE);
            clientCtx.close();
        }
    }

    private void handleConnect(ChannelHandlerContext clientCtx, Common.TunnelMsg msg) {
        if (!authenticated) {
            logger.warn("⚠️⚠️⚠️来自未经身份验证的客户端的连接尝试: {}", clientCtx.channel().remoteAddress());
            clientCtx.close();
            return;
        }
        String hostPort =  msg.getDataAsString();
        String[] split = hostPort.split(":");
        if (split.length != 2) {
            logger.warn("无效的目标地址格式: {}", hostPort);
            Common.TunnelMsg toMsg = new Common.TunnelMsg(Common.TYPE_CONNECT_FAIL, String.format("无效的目标地址格式: %s", hostPort));
            clientCtx.writeAndFlush(toMsg)
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        String host = split[0];
        int port = Integer.parseInt(split[1]);

        logger.info("连接到: {}:{} 来自客户端: {}", host, port, clientCtx.channel().remoteAddress());

        Bootstrap b = new Bootstrap();
        // 核心，使用同一个channel
        b.group(clientCtx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .handler(new TargetResponseHandler(clientCtx)); // 将 clientCtx 传入

        b.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                this.targetChannel = future.channel();
                clientCtx.writeAndFlush(new Common.TunnelMsg(Common.TYPE_CONNECT_SUCCESS, (ByteBuf) null));
            } else {
                logger.error("连接远程目标地址失败: {}, cause: {}", hostPort, future.cause().getMessage());
                String msgData = String.format("连接远程目标地址失败: %s, cause: %s", hostPort, future.cause().getMessage());
                Common.TunnelMsg toMsg = new Common.TunnelMsg(Common.TYPE_CONNECT_FAIL, msgData);
                clientCtx.writeAndFlush(toMsg)
                        .addListener(ChannelFutureListener.CLOSE);
            }
        });
    }

    private void handleData(ChannelHandlerContext clientCtx, Common.TunnelMsg msg) {
        if (targetChannel != null && targetChannel.isActive()) {
            targetChannel.writeAndFlush(msg.getData());
        } else {
            // 如果目标通道未准备好，可以选择关闭连接或忽略数据
            ReferenceCountUtil.release(msg);
        }
    }

    private void handleDisconnect(ChannelHandlerContext clientCtx) {
        logger.info("客户端主动断开了连接 {}", clientCtx.channel().remoteAddress());
        closeChannels();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        logger.info("客户端不活跃 {} 断开连接", ctx.channel().remoteAddress());
        closeChannels();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Exception in RemoteTunnelHandler", cause);
        closeChannels();
    }

    private void closeChannels() {
        if (targetChannel != null && targetChannel.isActive()) {
            targetChannel.close();
        }
    }
}