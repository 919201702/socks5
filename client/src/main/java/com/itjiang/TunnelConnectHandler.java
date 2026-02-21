package com.itjiang;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

public class TunnelConnectHandler extends SimpleChannelInboundHandler<Common.TunnelMsg> {
    private static final Logger logger = LoggerFactory.getLogger(TunnelConnectHandler.class);

    private final ChannelHandlerContext localCtx;
    private final String target;
    private final boolean connectMethod;
    private final ByteBuf initialPayload;
    private final boolean isHttps;

    public TunnelConnectHandler(ChannelHandlerContext localCtx, String target, boolean connectMethod, ByteBuf initialPayload, boolean isHttps) {
        this.localCtx = localCtx;
        this.target = target;
        this.connectMethod = connectMethod;
        this.initialPayload = initialPayload;
        this.isHttps = isHttps;
    }

    @Override
    public void channelActive(ChannelHandlerContext remoteCtx) {
        remoteCtx.write(new Common.TunnelMsg(Common.TYPE_AUTH, Config.CLIENT_AUTH_TOKEN));
        logger.info("{}请求连接: {}", isHttps ? "HTTPS" : "HTTP", target);
        remoteCtx.writeAndFlush(new Common.TunnelMsg(Common.TYPE_CONNECT, target));
    }

    @Override
    protected void channelRead0(ChannelHandlerContext remoteCtx, Common.TunnelMsg msg) {
        switch (msg.getType()) {
            case Common.TYPE_CONNECT_SUCCESS -> handleConnectSuccess(remoteCtx);
            case Common.TYPE_CONNECT_FAIL -> handleConnectFail(remoteCtx, msg);
            case Common.TYPE_DATA -> {
                if (localCtx.channel().isActive()) {
                    localCtx.writeAndFlush(msg.getData().retainedDuplicate());
                }
            }
            case Common.TYPE_DISCONNECT -> closeBoth(remoteCtx);
            default -> {
            }
        }
    }

    private void handleConnectSuccess(ChannelHandlerContext remoteCtx) {
        if (!localCtx.channel().isActive()) {
            closeBoth(remoteCtx);
            return;
        }

        if (connectMethod) {
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    new HttpResponseStatus(200, "Connection Established")
            );
            localCtx.writeAndFlush(response);
        }

        if (initialPayload != null && initialPayload.isReadable()) {
            remoteCtx.writeAndFlush(new Common.TunnelMsg(Common.TYPE_DATA, initialPayload.retainedDuplicate()));
        }
        ReferenceCountUtil.safeRelease(initialPayload);

        RelayPipeline.switchHttpToTunnelRelay(localCtx, remoteCtx.channel());

        remoteCtx.pipeline().remove(this);
        remoteCtx.pipeline().addLast(new RemoteDataRelayHandler(localCtx.channel()));
    }

    private void handleConnectFail(ChannelHandlerContext remoteCtx, Common.TunnelMsg msg) {
        String remoteMessage = "未知错误";
        if (msg.getData() != null && msg.getData().isReadable()) {
            remoteMessage = msg.getData().toString(StandardCharsets.UTF_8);
        }
        logger.warn("隧道连接失败: {} -> {}", target, remoteMessage);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.BAD_GATEWAY,
                io.netty.buffer.ByteBufUtil.writeUtf8(localCtx.alloc(), "tunnel connect fail: " + remoteMessage)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        localCtx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        ReferenceCountUtil.safeRelease(initialPayload);
        remoteCtx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        ReferenceCountUtil.safeRelease(initialPayload);
        closeBoth(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("TunnelConnectHandler 异常", cause);
        ReferenceCountUtil.safeRelease(initialPayload);
        closeBoth(ctx);
    }

    private void closeBoth(ChannelHandlerContext remoteCtx) {
        if (localCtx.channel().isActive()) {
            localCtx.close();
        }
        if (remoteCtx.channel().isActive()) {
            remoteCtx.close();
        }
    }
}
