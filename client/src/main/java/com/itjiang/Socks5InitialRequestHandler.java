package com.itjiang;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialResponse;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequest;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

@ChannelHandler.Sharable
public class Socks5InitialRequestHandler extends SimpleChannelInboundHandler<Socks5InitialRequest> {
    private static final Logger logger = LoggerFactory.getLogger(Socks5InitialRequestHandler.class);
    public static final Socks5InitialRequestHandler INSTANCE = new Socks5InitialRequestHandler();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Socks5InitialRequest msg) {
        if (Config.CLIENT_SOCKS5_AUTH_ENABLED) {
            if (!msg.authMethods().contains(Socks5AuthMethod.PASSWORD)) {
                ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.UNACCEPTED)).addListener(f -> ctx.close());
                return;
            }

            ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.PASSWORD));
            ctx.pipeline().remove(this);
            ctx.pipeline().remove(Socks5InitialRequestDecoder.class);
            ctx.pipeline().addLast(new Socks5PasswordAuthRequestDecoder());
            ctx.pipeline().addLast(Socks5PasswordAuthRequestHandler.INSTANCE);
            return;
        }

        ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH));
        ctx.pipeline().remove(this);
        ctx.pipeline().remove(Socks5InitialRequestDecoder.class);
        ctx.pipeline().addLast(new Socks5CommandRequestDecoder());
        ctx.pipeline().addLast(Socks5CommandRequestHandler.INSTANCE);
    }

    static void switchToCommandPipeline(ChannelHandlerContext ctx) {
        removeIfExists(ctx, Socks5PasswordAuthRequestHandler.class);
        removeIfExists(ctx, Socks5PasswordAuthRequestDecoder.class);
        ctx.pipeline().addLast(new Socks5CommandRequestDecoder());
        ctx.pipeline().addLast(Socks5CommandRequestHandler.INSTANCE);
    }

    private static void removeIfExists(ChannelHandlerContext ctx, Class<? extends ChannelHandler> handlerClass) {
        if (ctx.pipeline().get(handlerClass) != null) {
            ctx.pipeline().remove(handlerClass);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof IOException && cause.getMessage() != null && cause.getMessage().contains("Connection reset")) {
            ctx.close();
        } else {
            logger.error("Exception in Socks5InitialRequestHandler", cause);
            ctx.close();
        }
    }
}
