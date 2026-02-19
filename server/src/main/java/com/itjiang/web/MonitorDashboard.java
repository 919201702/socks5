package com.itjiang.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.itjiang.Config;
import com.itjiang.Monitor;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

public class MonitorDashboard {

    private static final Logger logger = LoggerFactory.getLogger(MonitorDashboard.class);
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static EventLoopGroup bossGroup;
    private static final AtomicBoolean isCleaned = new AtomicBoolean(false);
    public static void start() {
        bossGroup = new NioEventLoopGroup(1);
        try {
            Monitor.reloadStats(Config.SERVER_STATS_PATH);
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup)
             .channel(NioServerSocketChannel.class)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ch.pipeline()
                       .addLast(new HttpServerCodec())
                       .addLast(new HttpObjectAggregator(512 * 1024)) // 聚合HTTP消息
                       .addLast(new MonitorHandler());
                 }
             });

            ChannelFuture future = b.bind(Config.SERVER_MONITOR_HOST, Config.SERVER_MONITOR_PORT).sync();
            logger.info("监控面板启动，访问 http://{}:{}/", Config.SERVER_MONITOR_HOST, Config.SERVER_MONITOR_PORT);

            // 在一个新线程中等待关闭，避免阻塞主线程
            new Thread(() -> {
                try {
                    future.channel().closeFuture().sync();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    close();
                }
            }).start();

        } catch (InterruptedException e) {
            logger.error("监控面板启动失败", e);
            close();
        }
    }
    public static void close() {
        if (!isCleaned.compareAndSet(false, true)) {
            return;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        Monitor.shutdown(Config.SERVER_STATS_PATH);
    }

    private static class MonitorHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            if (!isInternalClient(ctx.channel().remoteAddress())) {
                sendError(ctx, HttpResponseStatus.FORBIDDEN);
                return;
            }
            // 只处理根路径的GET请求
            if (!request.method().equals(HttpMethod.GET) || !"/".equals(request.uri())) {
                sendError(ctx, HttpResponseStatus.NOT_FOUND);
                return;
            }
            sendResponse(ctx, GSON.toJson(Monitor.getStats()));
        }

        private boolean isInternalClient(SocketAddress remoteAddress) {
            if (!(remoteAddress instanceof InetSocketAddress inetSocketAddress)) {
                return false;
            }
            InetAddress address = inetSocketAddress.getAddress();
            if (address == null) {
                return false;
            }
            return address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress()
                    || isIpv6UniqueLocal(address);
        }



        private boolean isIpv6UniqueLocal(InetAddress address) {
            byte[] bytes = address.getAddress();
            return bytes.length == 16 && (bytes[0] & (byte) 0xFE) == (byte) 0xFC;
        }
        private void sendResponse(ChannelHandlerContext ctx, String content) {
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.OK,
                    Unpooled.copiedBuffer(content, StandardCharsets.UTF_8)
            );
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    status,
                    Unpooled.copiedBuffer("Failure: " + status + "\r\n", StandardCharsets.UTF_8)
            );
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error("MonitorHandler 异常", cause);
            ctx.close();
        }
    }
}
