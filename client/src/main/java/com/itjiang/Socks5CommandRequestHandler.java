package com.itjiang;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.itjiang.Config.SERVER_HOST;
import static com.itjiang.Config.SERVER_PORT;

@ChannelHandler.Sharable
public class Socks5CommandRequestHandler extends SimpleChannelInboundHandler<Socks5CommandRequest> {
    private static final Logger logger = LoggerFactory.getLogger(Socks5CommandRequestHandler.class);
    public static final Socks5CommandRequestHandler INSTANCE = new Socks5CommandRequestHandler();

    @Override
    protected void channelRead0(ChannelHandlerContext browserCtx, Socks5CommandRequest request) {
        if (request.type() != Socks5CommandType.CONNECT) {
            browserCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, request.dstAddrType()));
            browserCtx.close();
            return;
        }

        if (BlockHostFilter.getInstance().shouldBlock(request.dstAddr())) {
            logger.info("命中拦截规则，拒绝访问: {}", NetAddressFormatter.hostPort(request.dstAddr(), request.dstPort()));
            browserCtx.writeAndFlush(new DefaultSocks5CommandResponse(
                    Socks5CommandStatus.FORBIDDEN, request.dstAddrType()));
            browserCtx.close();
            return;
        }

        if (DirectAllowFilter.getInstance().shouldDirect(request.dstAddr())) {
            connectDirect(browserCtx, request);
            return;
        }
        connectTunnel(browserCtx, request);
    }

    private void connectTunnel(ChannelHandlerContext browserCtx, Socks5CommandRequest request) {
        RemoteConnectionHandler handler = new RemoteConnectionHandler(browserCtx, request);
        TcpClientConnector.connect(
                browserCtx,
                SERVER_HOST,
                SERVER_PORT,
                TunnelChannelInitializerFactory.newInitializer(handler),
                new TcpClientConnector.ConnectCallback() {
                    @Override
                    public void onSuccess(Channel channel) {
                    }

                    @Override
                    public void onFailure(Throwable cause) {
                        logger.error("连接远程服务器失败", cause);
                        browserCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, request.dstAddrType()));
                        browserCtx.close();
                    }
                }
        );
    }

    private void connectDirect(ChannelHandlerContext browserCtx, Socks5CommandRequest request) {
        TcpClientConnector.connect(
                browserCtx,
                request.dstAddr(),
                request.dstPort(),
                new io.netty.channel.ChannelInitializer<>() {
                    @Override
                    protected void initChannel(io.netty.channel.socket.SocketChannel ch) {
                    }
                },
                new TcpClientConnector.ConnectCallback() {
                    @Override
                    public void onSuccess(Channel targetChannel) {
                        logger.info("命中直连规则，直接访问: {}", NetAddressFormatter.hostPort(request.dstAddr(), request.dstPort()));
                        browserCtx.writeAndFlush(new DefaultSocks5CommandResponse(
                                Socks5CommandStatus.SUCCESS, request.dstAddrType(), request.dstAddr(), request.dstPort()));

                        RelayPipeline.switchSocksToTcpRelay(browserCtx, targetChannel);
                        targetChannel.pipeline().addLast(new TcpRelayHandler(browserCtx.channel()));
                    }

                    @Override
                    public void onFailure(Throwable cause) {
                        logger.warn("直连目标失败: {}", NetAddressFormatter.hostPort(request.dstAddr(), request.dstPort()), cause);
                        browserCtx.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, request.dstAddrType()));
                        browserCtx.close();
                    }
                }
        );
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof IOException && cause.getMessage() != null && cause.getMessage().contains("Connection reset")) {
            ctx.close();
        } else {
            logger.error("Exception in Socks5CommandRequestHandler", cause);
            ctx.close();
        }
    }
}
