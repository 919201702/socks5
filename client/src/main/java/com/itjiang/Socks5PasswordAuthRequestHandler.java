package com.itjiang;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthResponse;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequest;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

@ChannelHandler.Sharable
public class Socks5PasswordAuthRequestHandler extends SimpleChannelInboundHandler<Socks5PasswordAuthRequest> {
    private static final Logger logger = LoggerFactory.getLogger(Socks5PasswordAuthRequestHandler.class);
    public static final Socks5PasswordAuthRequestHandler INSTANCE = new Socks5PasswordAuthRequestHandler();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Socks5PasswordAuthRequest msg) {
        boolean authSuccess = Config.CLIENT_SOCKS5_USERNAME.equals(msg.username())
                && Config.CLIENT_SOCKS5_PASSWORD.equals(msg.password());
        if (!authSuccess) {
            ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE));
            ctx.close();
            return;
        }

        ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS));
        ctx.pipeline().remove(this);
        ctx.pipeline().remove(Socks5PasswordAuthRequestDecoder.class);
        ctx.pipeline().addLast(new Socks5CommandRequestDecoder());
        ctx.pipeline().addLast(Socks5CommandRequestHandler.INSTANCE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof IOException && cause.getMessage() != null && cause.getMessage().contains("Connection reset")) {
            ctx.close();
        } else {
            logger.error("Exception in Socks5PasswordAuthRequestHandler", cause);
            ctx.close();
        }
    }
}
