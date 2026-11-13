package com.github.wuzhudexiaopacai.internal.auth;

import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.security.HmacSigner;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 浏览器实时订阅凭证：后端签发短时 MQTT 凭证，前端 mqtt.js 经 WebSocket 直连 EMQX。
 * 生产环境应改用 WSS 并进一步收紧 ACL（M4）。
 */
@RestController
@RequestMapping("/api/mqtt")
public class MqttCredentialController {

    @Value("${coherer.mqtt.web.ws-url}")
    private String wsUrl;

    @Value("${coherer.mqtt.web.secret}")
    private String webSecret;

    @Value("${coherer.mqtt.web.credential-ttl-seconds:300}")
    private int ttlSeconds;

    @GetMapping("/credentials")
    public ApiResponse<MqttCredentialVO> credentials() {
        String clientId = "web-" + UUID.randomUUID().toString().replace("-", "");
        long expiry = System.currentTimeMillis() + ttlSeconds * 1000L;
        String username = "web:" + expiry;
        String password = HmacSigner.signHex(webSecret, clientId + "|" + username);
        return ApiResponse.ok(new MqttCredentialVO(wsUrl, clientId, username, password, ttlSeconds));
    }

    public record MqttCredentialVO(
            String url,
            String clientId,
            String username,
            String password,
            int ttlSeconds) {
    }
}
