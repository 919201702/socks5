package com.itjiang;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

public final class Common {

    private static final Logger logger = LoggerFactory.getLogger(Common.class); // 务必加上 Logger

    // --- AES-GCM 加密配置 ---
    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 16;
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final SecretKeySpec secretKeySpec = createAesKeyFromPassword(Config.ENCRYPT_KEY);

    // 消息类型定义保持不变...
    public static final byte TYPE_AUTH = 1;
    public static final byte TYPE_CONNECT = 2;
    public static final byte TYPE_DATA = 3;
    public static final byte TYPE_DISCONNECT = 4;
    public static final byte TYPE_CONNECT_SUCCESS = 5;
    public static final byte TYPE_CONNECT_FAIL = 6;

    private static SecretKeySpec createAesKeyFromPassword(String password) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] key = sha.digest(password.getBytes(StandardCharsets.UTF_8));
            key = Arrays.copyOf(key, 32);
            return new SecretKeySpec(key, "AES");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to create AES key", e);
        }
    }

    /**
     * [修复版] 加密
     */
    public static ByteBuf encrypt(ByteBuf src, ChannelHandlerContext ctx) throws Exception {
        // 1. 生成随机 IV
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);

        // 2. 准备 Cipher
        GCMParameterSpec params = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, params);

        // 3. 准备输入数据 (修复 CompositeByteBuf 问题)
        ByteBuffer nioIn;
        if (src.nioBufferCount() == 1) {
            nioIn = src.nioBuffer();
        } else {
            // 如果是组合 Buffer，需要拷贝到连续内存，否则 nioBuffer() 会报错
            byte[] bytes = new byte[src.readableBytes()];
            src.getBytes(src.readerIndex(), bytes); // 注意：这里不移动 readerIndex
            nioIn = ByteBuffer.wrap(bytes);
        }

        // 4. 分配输出 Buffer
        int payloadLength = src.readableBytes();
        int requiredOutputSize = cipher.getOutputSize(payloadLength);
        ByteBuf dst = ctx.alloc().ioBuffer(GCM_IV_LENGTH + requiredOutputSize);

        // 5. 写入 IV
        dst.writeBytes(iv);

        // 6. 加密
        ByteBuffer nioOut = dst.nioBuffer(dst.writerIndex(), dst.writableBytes());
        int encryptedBytes = cipher.doFinal(nioIn, nioOut);

        // 7. 更新输出 Buffer 的 writerIndex
        dst.writerIndex(dst.writerIndex() + encryptedBytes);

        return dst;
    }

    /**
     * [修复版] 解密
     */
    public static ByteBuf decrypt(ByteBuf src, ChannelHandlerContext ctx) throws Exception {
        if (src.readableBytes() < GCM_IV_LENGTH + GCM_TAG_LENGTH) {
            throw new IllegalArgumentException("Encrypted packet is too short.");
        }

        // 1. 读取 IV (src readerIndex +12)
        byte[] iv = new byte[GCM_IV_LENGTH];
        src.readBytes(iv);

        // 2. 准备 Cipher
        GCMParameterSpec params = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, params);

        // 3. 准备输入数据 (修复 readerIndex 一致性问题)
        int encryptedPayloadLen = src.readableBytes();
        ByteBuffer nioIn;

        if (src.nioBufferCount() == 1) {
            nioIn = src.nioBuffer();
            // 【关键】Cipher 读了数据，但 ByteBuf 不知道，必须手动跳过已读字节
            // 保持 Netty 的 readerIndex 状态正确
            src.skipBytes(encryptedPayloadLen);
        } else {
            byte[] temp = new byte[encryptedPayloadLen];
            src.readBytes(temp); // 这里会自动移动 readerIndex
            nioIn = ByteBuffer.wrap(temp);
        }

        // 4. 分配输出 Buffer
        int requiredOutputSize = cipher.getOutputSize(encryptedPayloadLen);
        ByteBuf dst = ctx.alloc().ioBuffer(requiredOutputSize);

        // 5. 解密
        ByteBuffer nioOut = dst.nioBuffer(dst.writerIndex(), dst.writableBytes());
        int decryptedBytes = cipher.doFinal(nioIn, nioOut);
        dst.writerIndex(dst.writerIndex() + decryptedBytes);

        return dst;
    }

    // TunnelMsg 类保持不变 ...
    public static class TunnelMsg extends AbstractReferenceCounted {
        private final byte type;
        private final ByteBuf data;

        public TunnelMsg(byte type, ByteBuf data) {
            this.type = type;
            this.data = data;
        }

        public TunnelMsg(byte type, String data) {
            this.type = type;
            if (data == null || data.isEmpty()) {
                this.data = Unpooled.EMPTY_BUFFER;
            } else {
                this.data = Unpooled.copiedBuffer(data, StandardCharsets.UTF_8);
            }
        }

        public TunnelMsg(byte type) {
            this.type = type;
            this.data = Unpooled.EMPTY_BUFFER;
        }

        public byte getType() { return type; }
        public ByteBuf getData() { return data; }
        public String getDataAsString() {
            if (data == null) return "";
            return data.toString(StandardCharsets.UTF_8);
        }
        @Override
        protected void deallocate() {
            if (data != null) data.release();
        }
        @Override
        public ReferenceCounted touch(Object hint) {
            if (data != null) data.touch(hint);
            return this;
        }
    }

    /**
     * 编码器
     */
    public static class TunnelEncoder extends MessageToByteEncoder<TunnelMsg> {
        @Override
        protected void encode(ChannelHandlerContext ctx, TunnelMsg msg, ByteBuf out) {
            ByteBuf encryptedBuf = null;
            try {
                ByteBuf rawData = msg.getData() != null ? msg.getData() : Unpooled.EMPTY_BUFFER;
                encryptedBuf = encrypt(rawData, ctx);

                // Protocol: [Length 4][Type 1][Encrypted Payload N]
                out.writeInt(1 + encryptedBuf.readableBytes());
                out.writeByte(msg.getType());
                out.writeBytes(encryptedBuf);

            } catch (Exception e) {
                // 【务必放开日志】
                logger.error("Failed to encrypt message. Closing connection.", e);
                ctx.close();
            } finally {
                if (encryptedBuf != null) {
                    encryptedBuf.release();
                }
            }
        }
    }

    /**
     * 解码器
     */
    public static class TunnelDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            // FrameDecoder 已经确保了长度正确，但防御性检查总是好的
            if (!in.isReadable()) return;

            byte type = in.readByte();
            ByteBuf decryptedBuf = null;
            try {
                // decrypt 内部会消耗掉 in 的剩余字节
                decryptedBuf = decrypt(in, ctx);
                out.add(new TunnelMsg(type, decryptedBuf));

            } catch (Exception e) {
                // 【务必放开日志】GCM 解密失败（Tag不匹配）通常意味着 Key 不对或者数据被篡改
                logger.error("Failed to decrypt message. Closing connection.", e);
                if (decryptedBuf != null) {
                    decryptedBuf.release();
                }
                ctx.close();
            }
        }
    }
}