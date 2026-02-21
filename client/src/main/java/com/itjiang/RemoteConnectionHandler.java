package com.itjiang;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 处理与远程服务器的隧道通信
public class RemoteConnectionHandler extends SimpleChannelInboundHandler<Common.TunnelMsg> {
    private static final Logger logger = LoggerFactory.getLogger(RemoteConnectionHandler.class);

    private final ChannelHandlerContext browserCtx;
    private final Socks5CommandRequest socksRequest;

    public RemoteConnectionHandler(ChannelHandlerContext browserCtx, Socks5CommandRequest socksRequest) {
        this.browserCtx = browserCtx;
        this.socksRequest = socksRequest;
    }

    // 当连接到远程服务器成功后
    @Override
    public void channelActive(ChannelHandlerContext remoteCtx) {
        remoteCtx.write(new Common.TunnelMsg(Common.TYPE_AUTH, Config.CLIENT_AUTH_TOKEN));

        String targetAddr = NetAddressFormatter.hostPort(socksRequest.dstAddr(), socksRequest.dstPort());
        logger.info("socks5请求连接: {}", targetAddr);
        remoteCtx.writeAndFlush(new Common.TunnelMsg(Common.TYPE_CONNECT, targetAddr));
    }

    @Override
    protected void channelRead0(ChannelHandlerContext remoteCtx, Common.TunnelMsg msg) {
        switch (msg.getType()) {
            case Common.TYPE_CONNECT_SUCCESS -> handleConnectSuccess(remoteCtx);
            case Common.TYPE_CONNECT_FAIL -> handleConnectFail(remoteCtx, msg);
            case Common.TYPE_DATA -> handleData(remoteCtx, msg);
            case Common.TYPE_DISCONNECT -> handleDisconnect(remoteCtx);
            default -> {}
        }
    }

    private void handleConnectSuccess(ChannelHandlerContext remoteCtx) {
        if (browserCtx.channel().isActive()) {
            // 远程连结成功，通知浏览器
            browserCtx.writeAndFlush(new DefaultSocks5CommandResponse(
                    Socks5CommandStatus.SUCCESS, socksRequest.dstAddrType(), socksRequest.dstAddr(), socksRequest.dstPort()));

            RelayPipeline.switchSocksToTunnelRelay(browserCtx, remoteCtx.channel());

            remoteCtx.pipeline().remove(this);
            remoteCtx.pipeline().addLast(new RemoteDataRelayHandler(browserCtx.channel()));
        } else {
            remoteCtx.close();
        }
    }

    private void handleConnectFail(ChannelHandlerContext remoteCtx, Common.TunnelMsg msg) {
        String remoteMsg = null;
        if (msg != null && msg.getData() != null && msg.getData().isReadable()) {
            remoteMsg = msg.getData().toString(StandardCharsets.UTF_8);
        }
        if (remoteMsg != null) {
            logger.warn("⚠️请求失败: {}, 服务端消息: {}", socksRequest.dstAddr(), remoteMsg);
        } else {
            logger.warn("⚠️请求失败: {}, 未知错误", socksRequest.dstAddr());
        }
        if (browserCtx.channel().isActive()) {
            browserCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, socksRequest.dstAddrType()))
                    .addListener(ChannelFutureListener.CLOSE);
        }
        remoteCtx.close();
    }

    private void handleData(ChannelHandlerContext browserCtx, Common.TunnelMsg msg) {
        if (browserCtx.channel().isActive()) {
            ByteBuf data = msg.getData();

            browserCtx.writeAndFlush(data.retainedDuplicate())
                    .addListener(future -> {
                        if (!future.isSuccess()) {
                            ReferenceCountUtil.safeRelease(data);
                            logger.error("写入浏览器失败", future.cause());
                        }
                    });
        }
    }

    private void handleDisconnect(ChannelHandlerContext remoteCtx) {
        closeChannels(remoteCtx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        closeChannels(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof IOException && cause.getMessage() != null && cause.getMessage().contains("Connection reset")) {
            closeChannels(ctx);
        } else {
            logger.error("远程连结通道异常", cause);
            closeChannels(ctx);
        }
    }

    private void closeChannels(ChannelHandlerContext remoteCtx) {
        if (browserCtx.channel().isActive()) {
            browserCtx.close();
        }
        if (remoteCtx.channel().isActive()) {
            remoteCtx.close();
        }
    }
}
