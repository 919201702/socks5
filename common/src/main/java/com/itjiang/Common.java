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
import java.security.SecureRandom;
import java.util.List;

public final class Common {
    // --- AES 加密配置 ---
    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // GCM 推荐的 IV 长度是 12 字节
    private static final int GCM_TAG_LENGTH = 16; // GCM 认证标签长度（128位）
    private static final SecureRandom secureRandom = new SecureRandom();

    // 消息类型
    public static final byte TYPE_AUTH = 1;
    public static final byte TYPE_CONNECT = 2;
    public static final byte TYPE_DATA = 3;
    public static final byte TYPE_DISCONNECT = 4;
    public static final byte TYPE_CONNECT_SUCCESS = 5;
    public static final byte TYPE_CONNECT_FAIL = 6;

    /**
     * 【零拷贝加密】
     * 输入：原始 ByteBuf
     * 输出：加密后的 ByteBuf (IV + EncryptedData + Tag)
     * 注意：输出的 ByteBuf 需要由调用者负责释放
     */
    public static ByteBuf encrypt(ByteBuf src, ChannelHandlerContext ctx) throws Exception {
        // 1. 生成 IV
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);

        // 2. 计算输出总长度 = IV长度 + 数据长度 + GCM Tag长度
        int payloadLength = src.readableBytes();
        int totalLength = GCM_IV_LENGTH + payloadLength + GCM_TAG_LENGTH;

        // 3. 分配一个新的 ByteBuf (直接内存)
        ByteBuf dst = ctx.alloc().ioBuffer(totalLength);

        // 4. 先写入 IV
        dst.writeBytes(iv);

        // 5. 准备 Cipher
        SecretKeySpec keySpec = new SecretKeySpec(Config.ENCRYPT_KEY.getBytes(StandardCharsets.UTF_8), "AES");
        GCMParameterSpec params = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, params);

        // 6. 【核心魔法】获取 NIO ByteBuffer 视图
        // src.nioBuffer() 不会拷贝内存，只是创建一个视图
        ByteBuffer nioIn = src.nioBuffer();

        // dst 现在的 writerIndex 在 IV 后面，我们要往后写
        // nioBuffer(index, length) 获取 dst 的可写区域视图
        ByteBuffer nioOut = dst.nioBuffer(dst.writerIndex(), payloadLength + GCM_TAG_LENGTH);

        // 7. 执行加密：直接从 nioIn 读，加密后写到 nioOut
        // 这一步完全在堆外内存或直接内存中进行，没有 byte[] 数组拷贝
        int encryptedBytes = cipher.doFinal(nioIn, nioOut);

        // 8. 更新 Netty ByteBuf 的 writerIndex
        dst.writerIndex(dst.writerIndex() + encryptedBytes);

        return dst;
    }

    /**
     * 【零拷贝解密】
     * 输入：加密的 ByteBuf (含 IV)
     * 输出：解密后的 ByteBuf
     */
    public static ByteBuf decrypt(ByteBuf src, ChannelHandlerContext ctx) throws Exception {
        // 检查长度
        if (src.readableBytes() < GCM_IV_LENGTH + GCM_TAG_LENGTH) {
            throw new IllegalArgumentException("数据包太短");
        }

        // 1. 读取 IV (IV 必须拷贝出来，因为 GCM Spec 需要它作为参数)
        byte[] iv = new byte[GCM_IV_LENGTH];
        src.readBytes(iv);

        // 2. 剩余部分就是 (Cipher text + Tag)
        int encryptedPayloadLen = src.readableBytes();

        // 3. 分配用于存放解密结果的 ByteBuf
        // 解密后长度 = 总长度 - Tag长度 (但在 GCM 中，Cipher.getOutputSize 可能会估算得大一点，这里直接分配够用就行)
        ByteBuf dst = ctx.alloc().ioBuffer(encryptedPayloadLen - GCM_TAG_LENGTH);

        // 4. 准备 Cipher
        SecretKeySpec keySpec = new SecretKeySpec(Config.ENCRYPT_KEY.getBytes(StandardCharsets.UTF_8), "AES");
        GCMParameterSpec params = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, params);

        // 5. 【核心魔法】NIO 视图
        java.nio.ByteBuffer nioIn = src.nioBuffer();

        // 注意：Cipher.update/doFinal 需要输出 buffer 有足够空间
        // 我们给它整个 dst 的 nioBuffer
        java.nio.ByteBuffer nioOut = dst.nioBuffer(dst.writerIndex(), dst.writableBytes());

        // 6. 执行解密
        int decryptedBytes = cipher.doFinal(nioIn, nioOut);

        // 7. 更新 writerIndex
        dst.writerIndex(dst.writerIndex() + decryptedBytes);

        return dst;
    }

    // 消息对象
    public static class TunnelMsg extends AbstractReferenceCounted{
        private final byte type;
        private final ByteBuf data;

        public TunnelMsg(byte type, ByteBuf data) {
            this.type = type;
            this.data = data;
        }
        public TunnelMsg(byte type, String data) {
            this.type = type;
            this.data = Unpooled.copiedBuffer(data, StandardCharsets.UTF_8);
        }
        public byte getType() {return type;}
        public ByteBuf getData() {return data;}
        public String getDataAsString() {
            return Unpooled.wrappedBuffer(data).toString(StandardCharsets.UTF_8);
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
     * +--------+-----------------------------+
     * | Length  | Type   |  Data             |
     * | 4 byte  | 1 byte |  data.length Byte |
     * +--------+-----------------------------+ </pre>
     */
    public static class TunnelEncoder extends MessageToByteEncoder<TunnelMsg> {
        @Override
        protected void encode(ChannelHandlerContext ctx, TunnelMsg msg, ByteBuf out) {
            ByteBuf encryptedBuf = null;
            try {
                ByteBuf rawData = msg.getData();
                if (rawData == null) {
                    rawData = Unpooled.EMPTY_BUFFER;
                }

                // 1. 调用零拷贝加密
                encryptedBuf = encrypt(rawData, ctx);

                // 2. 写入 LengthFieldBasedFrameDecoder 需要的长度头 (4字节)
                // 长度 = 1 (type) + 加密数据长度
                out.writeInt(1 + encryptedBuf.readableBytes());

                // 3. 写入 Type
                out.writeByte(msg.getType());

                // 4. 写入加密数据
                out.writeBytes(encryptedBuf);

            } catch (Exception e) {
                ctx.close();
                throw new RuntimeException("加密失败", e);
            } finally {
                // 必须释放临时创建的 encryptedBuf，因为它已经被写入到了 out 中 (writeBytes 会拷贝或转移)
                // 但为了安全起见，显式 release。
                // 注意：out.writeBytes(encryptedBuf) 实际上是把数据搬运过去了。
                // 如果是使用的 direct memory，writerIndex 移动了。
                if (encryptedBuf != null) {
                    encryptedBuf.release();
                }
            }
        }
    }

    public static class TunnelDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            // 这里进来的 in 已经被 LengthFieldBasedFrameDecoder 切好了，不含 Length 头
            if (in.readableBytes() < 1) return;

            byte type = in.readByte();

            // 剩下的全是加密数据 (IV + Cipher + Tag)
            // slice() 是浅拷贝，不占用新内存
            ByteBuf encryptedSlice = in.slice();

            try {
                // 1. 调用零拷贝解密
                ByteBuf decryptedBuf = decrypt(encryptedSlice, ctx);

                // 2. 封装成 Msg，传递给下一个 Handler
                // 注意：decryptedBuf 的生命周期交给了 TunnelMsg，最终由 Handler 负责 release
                out.add(new TunnelMsg(type, decryptedBuf));

                // 3. 移动 in 的读指针到末尾 (表示这段数据我处理完了)
                in.readerIndex(in.readerIndex() + encryptedSlice.readableBytes());

            } catch (Exception e) {
                ctx.close();
                throw new RuntimeException("解密失败", e);
            }
        }
    }
}
