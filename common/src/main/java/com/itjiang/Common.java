package com.itjiang;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
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
     * 使用 AES/GCM 模式加密数据.
     */
    public static byte[] encrypt(byte[] data) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);

        // 运行时从配置加载密钥
        SecretKeySpec secretKeySpec = new SecretKeySpec(Config.ENCRYPT_KEY.getBytes(StandardCharsets.UTF_8), "AES");

        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, gcmParameterSpec);

        byte[] encryptedData = cipher.doFinal(data);

        byte[] output = new byte[GCM_IV_LENGTH + encryptedData.length];
        System.arraycopy(iv, 0, output, 0, GCM_IV_LENGTH);
        System.arraycopy(encryptedData, 0, output, GCM_IV_LENGTH, encryptedData.length);
        return output;
    }

    /**
     * 使用 AES/GCM 模式解密数据.
     */
    public static byte[] decrypt(byte[] data) throws Exception {
        if (data.length < GCM_IV_LENGTH + GCM_TAG_LENGTH) {
            throw new IllegalArgumentException("无效的加密数据长度");
        }

        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(data, 0, iv, 0, GCM_IV_LENGTH);

        // 运行时从配置加载密钥
        SecretKeySpec secretKeySpec = new SecretKeySpec(Config.ENCRYPT_KEY.getBytes(StandardCharsets.UTF_8), "AES");

        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, gcmParameterSpec);

        return cipher.doFinal(data, GCM_IV_LENGTH, data.length - GCM_IV_LENGTH);
    }

    // 消息对象
    public record TunnelMsg(byte type, byte[] data) {}

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
            try {
                byte[] rawData = msg.data == null ? new byte[0] : msg.data;
                byte[] encrypted = encrypt(rawData);

                out.writeInt(1 + encrypted.length);
                out.writeByte(msg.type);
                out.writeBytes(encrypted);
            } catch (Exception e) {
                ctx.close();
                throw new RuntimeException("加密失败");
            }
        }
    }

    // 配套解码器
    public static class TunnelDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (in.readableBytes() < 1) return;

            byte type = in.readByte();
            byte[] encrypted = new byte[in.readableBytes()];
            in.readBytes(encrypted);

            try {
                byte[] decrypted = decrypt(encrypted);
                out.add(new TunnelMsg(type, decrypted));
            } catch (Exception e) {
                ctx.close();
                throw new RuntimeException("解密失败");
            }
        }
    }
}
