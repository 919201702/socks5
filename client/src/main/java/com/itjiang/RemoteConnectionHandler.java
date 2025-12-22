package com.itjiang;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

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
        // proxy.properties. 发送认证信息
        remoteCtx.write(new Common.TunnelMsg(Common.TYPE_AUTH, Config.AUTH_TOKEN.getBytes(StandardCharsets.UTF_8)));
        // 发送目标地址
        String targetAddr = String.format("%s:%d", socksRequest.dstAddr(), socksRequest.dstPort());
        logger.info("浏览器请求连接: {}", targetAddr);
        remoteCtx.writeAndFlush(new Common.TunnelMsg(Common.TYPE_CONNECT, targetAddr.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    protected void channelRead0(ChannelHandlerContext remoteCtx, Common.TunnelMsg msg) {
        switch (msg.type()) {
            case Common.TYPE_CONNECT_SUCCESS -> handleConnectSuccess(remoteCtx);
            case Common.TYPE_CONNECT_FAIL -> handleConnectFail(remoteCtx, msg);
            case Common.TYPE_DATA -> handleData(msg);
            case Common.TYPE_DISCONNECT -> handleDisconnect(remoteCtx);
            default -> ReferenceCountUtil.release(msg);
        }
    }

    private void handleConnectSuccess(ChannelHandlerContext remoteCtx) {
        // 确保对 browserCtx 的操作在其自身的 EventLoop 中执行，避免竞态条件
        if (browserCtx.channel().isActive()) {
            browserCtx.executor().execute(() -> {
                // 再次检查，因为在任务被执行前，channel 可能已经关闭
                if (browserCtx.channel().isActive()) {
                    // proxy.properties. 向浏览器发送 Socks5 成功响应
                    browserCtx.writeAndFlush(new DefaultSocks5CommandResponse(
                            Socks5CommandStatus.SUCCESS, socksRequest.dstAddrType(), socksRequest.dstAddr(), socksRequest.dstPort()));

                    // 清理并更新浏览器端的 pipeline
                    if (browserCtx.pipeline().get(Socks5CommandRequestHandler.class) != null) {
                        browserCtx.pipeline().remove(Socks5CommandRequestHandler.class);
                    }
                    if (browserCtx.pipeline().get(Socks5CommandRequestDecoder.class) != null) {
                        browserCtx.pipeline().remove(Socks5CommandRequestDecoder.class);
                    }
                    browserCtx.pipeline().addLast(new BrowserDataRelayHandler(remoteCtx.channel()));

                    // 清理并更新远程连接端的 pipeline
                    remoteCtx.pipeline().remove(this);
                    remoteCtx.pipeline().addLast(new RemoteDataRelayHandler(browserCtx.channel()));
                }
            });
        } else {
            // 如果浏览器连接已经关闭，我们也需要关闭远程连接
            remoteCtx.close();
        }
    }

    private void handleConnectFail(ChannelHandlerContext remoteCtx, Common.TunnelMsg msg) {
        String remoteMsg = Optional.ofNullable(msg).map(Common.TunnelMsg::data).map(String::new).orElse(null);
        logger.warn("⚠️请求失败: {}, 服务端消息: {}", socksRequest.dstAddr(), remoteMsg);
        if (browserCtx.channel().isActive()) {
            browserCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, socksRequest.dstAddrType()))
                     .addListener(future -> browserCtx.close());
        }
        remoteCtx.close();
    }

    private void handleData(Common.TunnelMsg msg) {
        if (browserCtx.channel().isActive()) {
            browserCtx.writeAndFlush(Unpooled.wrappedBuffer(msg.data()));
        } else {
            ReferenceCountUtil.release(msg);
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
            logger.error("Exception in RemoteConnectionHandler", cause);
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
