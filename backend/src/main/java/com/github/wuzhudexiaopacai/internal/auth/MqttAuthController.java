package com.github.wuzhudexiaopacai.internal.auth;

import com.github.wuzhudexiaopacai.internal.auth.dto.MqttAuthRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/auth")
@RequiredArgsConstructor
public class MqttAuthController {

    private final MqttAuthService mqttAuthService;

    /**
     * EMQX HTTP 认证器回调。按 EMQX 约定：HTTP 200 + {"result":"allow"|"deny"}，
     * 4xx/5xx 会被当作 ignore 继续认证链，因此失败也返回 200。
     */
    @PostMapping("/mqtt")
    public Map<String, String> authenticate(@RequestBody(required = false) MqttAuthRequest request) {
        boolean allow = request != null
                && mqttAuthService.authenticate(
                        request.getClientid(), request.getUsername(), request.getPassword());
        return Map.of("result", allow ? "allow" : "deny");
    }
}
