package com.github.wuzhudexiaopacai.common;

import java.security.SecureRandom;
import java.util.Base64;

public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private Ids() {
    }

    /** 产品标识：pk_ + 12 位十六进制 */
    public static String productKey() {
        return "pk_" + randomHex(6);
    }

    /** 设备标识：dev_ + 16 位十六进制（MQTT clientId） */
    public static String deviceId() {
        return "dev_" + randomHex(8);
    }

    /** 主机标识：host_ + 16 位十六进制（MQTT clientId） */
    public static String hostId() {
        return "host_" + randomHex(8);
    }

    /** 设备密钥：32 字节安全随机数，Base64URL 编码 */
    public static String deviceSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        char[] out = new char[bytes * 2];
        for (int i = 0; i < bytes; i++) {
            int value = buffer[i] & 0xFF;
            out[i * 2] = HEX[value >>> 4];
            out[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(out);
    }
}
