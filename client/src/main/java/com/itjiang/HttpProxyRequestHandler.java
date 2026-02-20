package com.itjiang;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.net.URI;

import static com.itjiang.Config.SERVER_HOST;
import static com.itjiang.Config.SERVER_PORT;

@ChannelHandler.Sharable
public class HttpProxyRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger logger = LoggerFactory.getLogger(HttpProxyRequestHandler.class);
    private final boolean isHttps;

    public HttpProxyRequestHandler(boolean isHttps) {
        this.isHttps = isHttps;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext localCtx, FullHttpRequest request) {
        boolean isConnect = request.method().equals(HttpMethod.CONNECT);
        if (isHttps && !isConnect) {
            sendError(localCtx, HttpResponseStatus.METHOD_NOT_ALLOWED, "HTTPS 代理仅支持 CONNECT 方法");
            return;
        }
        ProxyTarget target;
        ByteBuf initialPayload = null;
        try {
            if (isConnect) {
                target = parseConnectTarget(request.uri());
            } else {
                target = parseHttpTarget(request);
                FullHttpRequest proxied = rebuildHttpRequest(request, target.path());
                initialPayload = encodeRequest(localCtx.alloc(), proxied);
                proxied.release();
            }
        } catch (IllegalArgumentException ex) {
            sendError(localCtx, HttpResponseStatus.BAD_REQUEST, ex.getMessage());
            return;
        }

        connectTunnel(localCtx, target, isConnect, initialPayload);
    }

    private void connectTunnel(ChannelHandlerContext localCtx, ProxyTarget target, boolean isConnect, ByteBuf initialPayload) {
        SslContext sslCtx;
        try {
            sslCtx = SslContextBuilder.forClient()
                    .sslProvider(SslProvider.OPENSSL)
                    .trustManager(Config.SERVER_CERT)
                    .build();
        } catch (SSLException e) {
            throw new RuntimeException("ssl证书配置异常", e);
        }

        Bootstrap b = new Bootstrap();
        b.group(localCtx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(sslCtx.newHandler(ch.alloc(), SERVER_HOST, SERVER_PORT));
                        ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(128 * 1024 * 1024, 0, 4, 0, 4));
                        ch.pipeline().addLast(new Common.TunnelMsgCodec());
                        ch.pipeline().addLast(new TunnelConnectHandler(localCtx, target.hostPort(), isConnect, initialPayload, isHttps));
                    }
                });

        b.connect(SERVER_HOST, SERVER_PORT).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                logger.error("连接远程服务器失败", future.cause());
                if (initialPayload != null) {
                    ReferenceCountUtil.safeRelease(initialPayload);
                }
                sendError(localCtx, HttpResponseStatus.BAD_GATEWAY, "连接远程服务器失败");
            }
        });
    }

    private ProxyTarget parseConnectTarget(String uri) {
        String value = uri == null ? "" : uri.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("CONNECT 请求缺少目标地址");
        }
        int idx = value.lastIndexOf(':');
        if (idx <= 0 || idx >= value.length() - 1) {
            throw new IllegalArgumentException("CONNECT 目标地址格式应为 host:port");
        }
        String host = value.substring(0, idx);
        int port = parsePort(value.substring(idx + 1));
        return new ProxyTarget(host, port, "");
    }

    private ProxyTarget parseHttpTarget(FullHttpRequest request) {
        URI uri = URI.create(request.uri());
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || host.isBlank()) {
            String hostHeader = request.headers().get(HttpHeaderNames.HOST);
            if (hostHeader == null || hostHeader.isBlank()) {
                throw new IllegalArgumentException("HTTP 请求缺少 Host");
            }
            int idx = hostHeader.lastIndexOf(':');
            if (idx > 0 && hostHeader.indexOf(':') == idx) {
                host = hostHeader.substring(0, idx);
                port = parsePort(hostHeader.substring(idx + 1));
            } else {
                host = hostHeader;
                port = 80;
            }
        } else if (port < 0) {
            port = 80;
        }

        String rawPath = uri.getRawPath();
        String path = (rawPath == null || rawPath.isEmpty()) ? "/" : rawPath;
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            path = path + "?" + uri.getRawQuery();
        }
        return new ProxyTarget(host, port, path);
    }

    private FullHttpRequest rebuildHttpRequest(FullHttpRequest request, String path) {
        ByteBuf copied = request.content().copy();
        FullHttpRequest proxied = new DefaultFullHttpRequest(request.protocolVersion(), request.method(), path, copied);
        proxied.headers().setAll(request.headers());
        proxied.headers().set(HttpHeaderNames.HOST, request.headers().get(HttpHeaderNames.HOST));
        proxied.headers().remove(HttpHeaderNames.PROXY_CONNECTION);
        proxied.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        proxied.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, proxied.content().readableBytes());
        return proxied;
    }

    private ByteBuf encodeRequest(ByteBufAllocator allocator, FullHttpRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append(request.method()).append(' ').append(request.uri()).append(' ')
                .append(request.protocolVersion().text()).append("\r\n");
        request.headers().forEach(h -> sb.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n"));
        sb.append("\r\n");
        ByteBuf out = ByteBufUtil.writeAscii(allocator, sb.toString());
        if (!request.content().isReadable()) {
            return out;
        }
        out.writeBytes(request.content(), request.content().readerIndex(), request.content().readableBytes());
        return out;
    }

    private int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("端口不合法: " + value);
            }
            return port;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("端口不是数字: " + value, ex);
        }
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String msg) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                ByteBufUtil.writeUtf8(ctx.alloc(), msg));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("HttpProxyRequestHandler 异常", cause);
        ctx.close();
    }

    private record ProxyTarget(String host, int port, String path) {
        String hostPort() {
            if (host.contains(":") && !host.startsWith("[") && !host.endsWith("]")) {
                return "[" + host + "]:" + port;
            }
            return host + ":" + port;
        }
    }
}
