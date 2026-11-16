package com.github.wuzhudexiaopacai.internal.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.wuzhudexiaopacai.auth.entity.SysUser;
import com.github.wuzhudexiaopacai.auth.mapper.SysUserMapper;
import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.security.HmacSigner;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 浏览器实时订阅凭证：仅登录用户可获取；用户名内嵌 userId 与过期时间，
 * ACL 回调据此校验用户的空间数据范围。
 */
@RestController
@RequestMapping("/api/mqtt")
@RequiredArgsConstructor
public class MqttCredentialController {

    private final SysUserMapper userMapper;

    @Value("${coherer.mqtt.web.ws-url}")
    private String wsUrl;

    @Value("${coherer.mqtt.web.secret}")
    private String webSecret;

    @Value("${coherer.mqtt.web.credential-ttl-seconds:300}")
    private int ttlSeconds;

    @GetMapping("/credentials")
    public ApiResponse<MqttCredentialVO> credentials(Authentication authentication) {
        SysUser user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, authentication.getName())
                .last("limit 1"));
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new BizException(401, "用户不可用");
        }
        String clientId = "web-" + UUID.randomUUID().toString().replace("-", "");
        long expiry = System.currentTimeMillis() + ttlSeconds * 1000L;
        String username = "web:" + user.getId() + ":" + expiry;
        String password = HmacSigner.signHex(webSecret, clientId + "|" + username);
        return ApiResponse.ok(new MqttCredentialVO(
                wsUrl, clientId, username, password, ttlSeconds,
                user.getTenantId(), user.getSpaceId()));
    }

    public record MqttCredentialVO(
            String url,
            String clientId,
            String username,
            String password,
            int ttlSeconds,
            String tenantId,
            Long spaceId) {
    }
}
