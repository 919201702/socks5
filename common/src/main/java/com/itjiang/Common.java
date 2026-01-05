package com.itjiang;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class Common {
    // 消息类型
    public static final byte TYPE_AUTH = 1;
    public static final byte TYPE_CONNECT = 2;
    public static final byte TYPE_DATA = 3;
    public static final byte TYPE_DISCONNECT = 4;
    public static final byte TYPE_CONNECT_SUCCESS = 5;
    public static final byte TYPE_CONNECT_FAIL = 6;

    public static class TunnelMsg extends AbstractReferenceCounted {
        private final byte type;
        private final ByteBuf data;

        public TunnelMsg(byte type) {
            this.type = type;
            this.data = Unpooled.EMPTY_BUFFER;
        }
        public TunnelMsg(byte type, ByteBuf data) {
            this.type = type;
            this.data = data;
        }
        public TunnelMsg(byte type, String data) {
            this.type = type;
            this.data = (data == null || data.isEmpty()) ? Unpooled.EMPTY_BUFFER : Unpooled.copiedBuffer(data, StandardCharsets.UTF_8);
        }
        public byte getType() { return type; }
        public ByteBuf getData() { return data; }
        public String getDataAsString() { return data.toString(StandardCharsets.UTF_8); }
        @Override protected void deallocate() { if (data != null) data.release(); }
        @Override public ReferenceCounted touch(Object hint) { if (data != null) data.touch(hint); return this; }
    }

    // 序列化，前置依赖LengthFieldBasedFrameDecoder
    // 协议格式: [Length 4][Type 1][Raw Data N]
    public static class TunnelMsgCodec extends ByteToMessageCodec<TunnelMsg> {
        @Override
        protected void encode(ChannelHandlerContext ctx, TunnelMsg msg, ByteBuf out) throws Exception {
            int dataLen = msg.getData().readableBytes();
            out.writeInt(1 + dataLen);
            out.writeByte(msg.getType());
            out.writeBytes(msg.getData().duplicate());
        }
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            if (!in.isReadable()) return;
            byte type = in.readByte();
            ByteBuf data = in.readRetainedSlice(in.readableBytes());
            out.add(new TunnelMsg(type, data));
        }
    }
}