package com.itjiang;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;

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

    // --- AES-GCM 加密配置 ---
    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // GCM 推荐的 IV 长度是 12 字节
    private static final int GCM_TAG_LENGTH = 16; // GCM 认证标签长度（128位）
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final SecretKeySpec secretKeySpec = createAesKeyFromPassword(Config.ENCRYPT_KEY);


    // 消息类型
    public static final byte TYPE_AUTH = 1;
    public static final byte TYPE_CONNECT = 2;
    public static final byte TYPE_DATA = 3;
    public static final byte TYPE_DISCONNECT = 4;
    public static final byte TYPE_CONNECT_SUCCESS = 5;
    public static final byte TYPE_CONNECT_FAIL = 6;

    /**
     * [已修复] 从一个任意字符串密码安全地生成一个 AES 密钥。
     * 使用 SHA-256 哈希算法确保输出的密钥长度恒为 32 字节 (256位)，符合 AES 要求。
     *
     * @param password 配置文件中的原始密钥字符串
     * @return 符合 AES 规范的 SecretKeySpec
     */
    private static SecretKeySpec createAesKeyFromPassword(String password) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] key = sha.digest(password.getBytes(StandardCharsets.UTF_8));
            // AES-256 使用 32 字节的密钥
            key = Arrays.copyOf(key, 32);
            return new SecretKeySpec(key, "AES");
        } catch (NoSuchAlgorithmException e) {
            // 如果 JVM 不支持 SHA-256，这是个严重问题，直接让程序崩溃
            throw new RuntimeException("Failed to create AES key", e);
        }
    }


    /**
     * 【零拷贝加密】
     * 输入：原始 ByteBuf
     * 输出：加密后的 ByteBuf (IV + EncryptedData + Tag)
     * 注意：输出的 ByteBuf 需要由调用者负责释放
     */
    public static ByteBuf encrypt(ByteBuf src, ChannelHandlerContext ctx) throws Exception {
        // 1. 生成随机 IV
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);

        // 2. 准备 Cipher
        GCMParameterSpec params = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, params);

        // 3. 【核心】获取输入和输出的 NIO ByteBuffer 视图，以实现零拷贝
        ByteBuffer nioIn = src.nioBuffer();
        int payloadLength = src.readableBytes();
        int requiredOutputSize = cipher.getOutputSize(payloadLength);
        ByteBuf dst = ctx.alloc().ioBuffer(GCM_IV_LENGTH + requiredOutputSize);

        // 4. 先写入 IV
        dst.writeBytes(iv);

        // 5. 获取用于加密的输出区域的视图
        ByteBuffer nioOut = dst.nioBuffer(dst.writerIndex(), dst.writableBytes());

        // 6. 执行加密：直接从 nioIn 读，加密后写到 nioOut
        int encryptedBytes = cipher.doFinal(nioIn, nioOut);

        // 7. 更新 Netty ByteBuf 的 writerIndex
        dst.writerIndex(dst.writerIndex() + encryptedBytes);

        return dst;
    }

    /**
     * 【解密】
     * 输入：加密的 ByteBuf (IV + EncryptedData + Tag)
     * 输出：解密后的 ByteBuf
     */
    public static ByteBuf decrypt(ByteBuf src, ChannelHandlerContext ctx) throws Exception {
        if (src.readableBytes() < GCM_IV_LENGTH + GCM_TAG_LENGTH) {
            throw new IllegalArgumentException("Encrypted packet is too short to be valid.");
        }

        // 1. 读取 IV
        byte[] iv = new byte[GCM_IV_LENGTH];
        src.readBytes(iv);

        // 2. 准备 Cipher
        GCMParameterSpec params = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, params);

        // 3. 分配用于存放解密后明文的内存
        int encryptedPayloadLen = src.readableBytes();
        int requiredOutputSize = cipher.getOutputSize(encryptedPayloadLen);
        ByteBuf dst = ctx.alloc().ioBuffer(requiredOutputSize);

        // 4. 【核心修复点】安全地获取输入 ByteBuffer，处理 CompositeByteBuf 的情况
        ByteBuffer nioIn;
        if (src.nioBufferCount() == 1) {
            // 理想情况：连续内存，直接获取视图，零拷贝
            nioIn = src.nioBuffer();
        } else {
            // 罕见情况：非连续内存 (CompositeByteBuf)，需要拷贝到连续数组中
//            logger.debug("Decrypting a non-contiguous CompositeByteBuf.");
            byte[] temp = new byte[src.readableBytes()];
            src.readBytes(temp);
            nioIn = ByteBuffer.wrap(temp);
        }

        // 5. 获取输出区域的视图
        ByteBuffer nioOut = dst.nioBuffer(dst.writerIndex(), dst.writableBytes());

        // 6. 解密
        int decryptedBytes = cipher.doFinal(nioIn, nioOut);
        dst.writerIndex(dst.writerIndex() + decryptedBytes);

        return dst;
    }

    // 消息对象
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

        public byte getType() {
            return type;
        }

        public ByteBuf getData() {
            return data;
        }

        public String getDataAsString() {
            if (data == null) return "";
            return data.toString(StandardCharsets.UTF_8);
        }

        @Override
        protected void deallocate() {
            if (data != null) {
                data.release();
            }
        }

        @Override
        public ReferenceCounted touch(Object hint) {
            if (data != null) data.touch(hint);
            return this;
        }
    }

    /**
     * 编码器，必须依赖LengthFieldBasedFrameDecoder处理tcp半包粘包问题
     * <pre>
     * +--------+------------------------------------+
     * | Length | Type   |  Encrypted Data            |
     * | 4 byte | 1 byte |  (IV + Ciphertext + Tag)   |
     * +--------+------------------------------------+ </pre>
     */
    public static class TunnelEncoder extends MessageToByteEncoder<TunnelMsg> {
        @Override
        protected void encode(ChannelHandlerContext ctx, TunnelMsg msg, ByteBuf out) {
            ByteBuf encryptedBuf = null;
            try {
                ByteBuf rawData = msg.getData() != null ? msg.getData() : Unpooled.EMPTY_BUFFER;

                // 1. 调用加密
                encryptedBuf = encrypt(rawData, ctx);

                // 2. 写入 LengthFieldBasedFrameDecoder 需要的长度头
                // 长度 = 1 (type) + 加密数据长度
                out.writeInt(1 + encryptedBuf.readableBytes());

                // 3. 写入 Type
                out.writeByte(msg.getType());

                // 4. 写入加密数据
                out.writeBytes(encryptedBuf);

            } catch (Exception e) {
//                logger.error("Failed to encrypt message. Closing connection.", e);
                ctx.close();
            } finally {
                // 必须释放临时创建的 encryptedBuf，因为它已经被 writeBytes 拷贝或转移到 out 中了
                if (encryptedBuf != null) {
                    encryptedBuf.release();
                }
            }
        }
    }

    /**
     * 解码器
     * 输入的 ByteBuf 是已经被 LengthFieldBasedFrameDecoder 裁剪过的，不包含4字节的长度头。
     */
    public static class TunnelDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            // FrameDecoder 保证了 'in' 是一个完整的消息帧
            if (in.readableBytes() < 1) {
                return; // 不可能的情况，但作为防御性编程
            }

            byte type = in.readByte();
            ByteBuf decryptedBuf = null;
            try {
                // 1. 将剩余部分（加密数据）解密
                // 'in' 的 readerIndex 会在 decrypt 方法内部被消耗
                decryptedBuf = decrypt(in, ctx);

                // 2. 封装成 Msg 并传递给下一个 Handler
                // decryptedBuf 的所有权转移给了 TunnelMsg
                out.add(new TunnelMsg(type, decryptedBuf));

            } catch (Exception e) {
//                logger.error("Failed to decrypt message. Closing connection.", e);
                // 释放解密过程中可能已分配的内存
                if (decryptedBuf != null) {
                    decryptedBuf.release();
                }
                ctx.close();
            }
        }
    }
}
