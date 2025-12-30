package com.itjiang.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

public class MonitorDashboard {

    private static final Logger logger = LoggerFactory.getLogger(MonitorDashboard.class);
    private static final int PORT = 80; // 监控端口
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public static void start() {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        try {
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

            ChannelFuture future = b.bind(PORT).sync();
            logger.info("监控面板启动，访问 http://127.0.0.1:{}/", PORT);

            // 在一个新线程中等待关闭，避免阻塞主线程
            new Thread(() -> {
                try {
                    future.channel().closeFuture().sync();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    bossGroup.shutdownGracefully();
                }
            }).start();

        } catch (InterruptedException e) {
            logger.error("监控面板启动失败", e);
            Thread.currentThread().interrupt();
            bossGroup.shutdownGracefully();
        }
    }

    private static class MonitorHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            // 只处理根路径的GET请求
            if (!request.method().equals(HttpMethod.GET) || !"/".equals(request.uri())) {
                sendError(ctx, HttpResponseStatus.NOT_FOUND);
                return;
            }
            sendResponse(ctx, GSON.toJson(Monitor.getStats()));
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
