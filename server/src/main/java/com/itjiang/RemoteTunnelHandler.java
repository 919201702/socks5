package com.itjiang;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.nio.NioSocketChannel;

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
            default -> {}
        }
    }

    private void handleAuth(ChannelHandlerContext clientCtx, Common.TunnelMsg msg) {
        String token = msg.getDataAsString();
        if (Config.SERVER_AUTH_TOKEN_LIST.contains(token)) {
            authenticated = true;
            clientCtx.pipeline().addBefore(clientCtx.name(), "trafficMonitorHandler", new TrafficMonitorHandler(token));
            logger.info("新的客户端连接: {}, Token: {}", clientCtx.channel().remoteAddress(), token);
        } else {
            logger.warn("非法客户端连接: {}", clientCtx.channel().remoteAddress());
            Common.TunnelMsg toMsg = new Common.TunnelMsg(Common.TYPE_CONNECT_FAIL, "token验证失败");
            clientCtx.writeAndFlush(toMsg)
                    .addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void handleConnect(ChannelHandlerContext clientCtx, Common.TunnelMsg msg) {
        if (!authenticated) {
            logger.warn("⚠️⚠️⚠️来自未经身份验证的客户端的连接尝试: {}", clientCtx.channel().remoteAddress());
            clientCtx.close();
            return;
        }
        String hostPort =  msg.getDataAsString();
        HostPort parsed;
        try {
            parsed = parseHostPort(hostPort);
        } catch (IllegalArgumentException ex) {
            logger.warn("无效的目标地址格式: {}, cause: {}", hostPort, ex.getMessage());
            Common.TunnelMsg toMsg = new Common.TunnelMsg(Common.TYPE_CONNECT_FAIL, String.format("无效的目标地址格式: %s", hostPort));
            clientCtx.writeAndFlush(toMsg)
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        String host = parsed.host();
        int port = parsed.port();

        logger.info("连接到: {}:{} 来自客户端: {}", host, port, clientCtx.channel().remoteAddress());

        Bootstrap b = new Bootstrap();
        // 核心，使用同一个channel
        b.group(clientCtx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .handler(new TargetResponseHandler(clientCtx)); // 将 clientCtx 传入

        b.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                this.targetChannel = future.channel();
                clientCtx.writeAndFlush(new Common.TunnelMsg(Common.TYPE_CONNECT_SUCCESS));
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
            // 需要交给下一个 Channel 发送，使用带独立的索引和标记的buf视图
            targetChannel.writeAndFlush(msg.getData().retainedDuplicate());
        }
    }

    // 预防Client数据积压，remote端暂停写入
    @Override
    public void channelWritabilityChanged(ChannelHandlerContext clientCtx) throws Exception {
        boolean canWrite = clientCtx.channel().isWritable();
        if (targetChannel != null) {
            targetChannel.config().setAutoRead(canWrite);
        }
        super.channelWritabilityChanged(clientCtx);
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



    private HostPort parseHostPort(String hostPort) {
        if (hostPort == null || hostPort.isBlank()) {
            throw new IllegalArgumentException("hostPort 为空");
        }
        String value = hostPort.trim();
        String host;
        String portText;

        if (value.startsWith("[")) {
            int bracketCloseIdx = value.indexOf(']');
            if (bracketCloseIdx < 0 || bracketCloseIdx + 2 > value.length() || value.charAt(bracketCloseIdx + 1) != ':') {
                throw new IllegalArgumentException("不支持的 IPv6 格式");
            }
            host = value.substring(1, bracketCloseIdx);
            portText = value.substring(bracketCloseIdx + 2);
        } else {
            int lastColon = value.lastIndexOf(':');
            if (lastColon <= 0 || lastColon == value.length() - 1) {
                throw new IllegalArgumentException("缺少端口");
            }
            host = value.substring(0, lastColon);
            portText = value.substring(lastColon + 1);
        }

        int parsedPort;
        try {
            parsedPort = Integer.parseInt(portText);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("端口不是数字", ex);
        }
        if (parsedPort < 1 || parsedPort > 65535) {
            throw new IllegalArgumentException("端口范围非法");
        }
        if (host.isBlank()) {
            throw new IllegalArgumentException("目标地址为空");
        }
        return new HostPort(host, parsedPort);
    }

    private record HostPort(String host, int port) { }

    private void closeChannels() {
        if (targetChannel != null && targetChannel.isActive()) {
            targetChannel.close();
        }
    }
}