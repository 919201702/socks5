package com.itjiang;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthResponse;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequest;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus;

@ChannelHandler.Sharable
public class Socks5PasswordAuthRequestHandler extends SimpleChannelInboundHandler<Socks5PasswordAuthRequest> {

    public static final Socks5PasswordAuthRequestHandler INSTANCE = new Socks5PasswordAuthRequestHandler();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Socks5PasswordAuthRequest msg) {
        boolean success = Config.CLIENT_SOCKS5_USERNAME.equals(msg.username())
                && Config.CLIENT_SOCKS5_PASSWORD.equals(msg.password());

        if (!success) {
            ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE)).addListener(f -> ctx.close());
            return;
        }

        ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS));
        Socks5InitialRequestHandler.switchToCommandPipeline(ctx);
    }
}
