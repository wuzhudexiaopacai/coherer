package com.github.wuzhudexiaopacai.ota.service;

import com.github.wuzhudexiaopacai.security.HmacSigner;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** OTA 下载短时签名：设备无需 JWT，凭签名 URL 下载自己的固件。 */
@Service
public class OtaDownloadSigner {

    private final byte[] secret;

    public OtaDownloadSigner(@Value("${coherer.ota.secret}") String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalStateException("coherer.ota.secret 至少需要 16 字符");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(Long firmwareId, String deviceId, long expiresAtMillis) {
        return HmacSigner.signHex(new String(secret, StandardCharsets.UTF_8),
                firmwareId + "|" + deviceId + "|" + expiresAtMillis);
    }

    public boolean verify(Long firmwareId, String deviceId, long expiresAtMillis, String signature) {
        if (signature == null || expiresAtMillis < System.currentTimeMillis()) {
            return false;
        }
        String expected = sign(firmwareId, deviceId, expiresAtMillis);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }
}
