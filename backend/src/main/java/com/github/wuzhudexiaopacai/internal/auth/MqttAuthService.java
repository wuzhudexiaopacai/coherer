package com.github.wuzhudexiaopacai.internal.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.security.HmacSigner;
import com.github.wuzhudexiaopacai.security.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * MQTT 接入认证：
 *
 * <pre>
 * clientId  = deviceId
 * username  = 毫秒时间戳
 * password  = HMAC-SHA256(deviceSecret, clientId + "|" + username) 的小写十六进制
 * </pre>
 *
 * 时间戳允许 ±5 分钟偏差；设备被禁用则拒绝。后端服务账号（ingest）走独立口令。
 */
@Service
@RequiredArgsConstructor
public class MqttAuthService {

    private static final Logger log = LoggerFactory.getLogger(MqttAuthService.class);
    private static final long MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L;

    private final DeviceMapper deviceMapper;
    private final SecretCipher secretCipher;

    @Value("${coherer.mqtt.ingest.client-id}")
    private String ingestClientId;

    @Value("${coherer.mqtt.ingest.username}")
    private String ingestUsername;

    @Value("${coherer.mqtt.ingest.secret}")
    private String ingestSecret;

    @Value("${coherer.mqtt.web.secret}")
    private String webSecret;

    public boolean authenticate(String clientId, String username, String password) {
        if (!StringUtils.hasText(clientId)
                || !StringUtils.hasText(username)
                || !StringUtils.hasText(password)) {
            return false;
        }

        // 后端服务账号
        if (ingestClientId.equals(clientId) && ingestUsername.equals(username)) {
            return constantTimeEquals(password, ingestSecret);
        }

        // 浏览器实时订阅账号（后端签发短时凭证）
        if (username.startsWith("web:")) {
            if (!clientId.startsWith("web-")) {
                return false;
            }
            long expiry;
            try {
                expiry = Long.parseLong(username.substring(4));
            } catch (NumberFormatException e) {
                return false;
            }
            if (expiry < System.currentTimeMillis()) {
                log.debug("认证失败 clientId={} 原因=Web 凭证已过期", clientId);
                return false;
            }
            String expected = HmacSigner.signHex(webSecret, clientId + "|" + username);
            return constantTimeEquals(password.toLowerCase(Locale.ROOT), expected);
        }

        // 设备签名认证
        if (!username.chars().allMatch(Character::isDigit)) {
            log.debug("认证失败 clientId={} 原因=username 非时间戳", clientId);
            return false;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(username);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(System.currentTimeMillis() - timestamp) > MAX_CLOCK_SKEW_MS) {
            log.debug("认证失败 clientId={} 原因=时间戳超出 ±5 分钟", clientId);
            return false;
        }

        Device device = deviceMapper.selectOne(Wrappers.<Device>lambdaQuery()
                .eq(Device::getDeviceId, clientId)
                .last("limit 1"));
        if (device == null) {
            log.debug("认证失败 clientId={} 原因=设备不存在", clientId);
            return false;
        }
        if ("DISABLED".equals(device.getStatus())) {
            log.debug("认证失败 clientId={} 原因=设备已禁用", clientId);
            return false;
        }

        String secret = secretCipher.decrypt(device.getSecretCipher());
        String expected = HmacSigner.signHex(secret, clientId + "|" + username);
        return constantTimeEquals(password.toLowerCase(Locale.ROOT), expected);
    }

    private boolean constantTimeEquals(String actual, String expected) {
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
