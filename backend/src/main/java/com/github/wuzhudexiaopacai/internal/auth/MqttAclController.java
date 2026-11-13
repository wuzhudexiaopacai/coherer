package com.github.wuzhudexiaopacai.internal.auth;

import com.github.wuzhudexiaopacai.internal.auth.dto.MqttAclRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/auth")
@RequiredArgsConstructor
public class MqttAclController {

    private final MqttAclService mqttAclService;

    /** EMQX HTTP 授权回调：200 + {"result":"allow"|"deny"}。 */
    @PostMapping("/acl")
    public Map<String, String> authorize(@RequestBody(required = false) MqttAclRequest request) {
        boolean allow = request != null && mqttAclService.authorize(
                request.getClientid(), request.getUsername(), request.getTopic(), request.getAction());
        return Map.of("result", allow ? "allow" : "deny");
    }
}
