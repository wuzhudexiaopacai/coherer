package com.github.wuzhudexiaopacai.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.wuzhudexiaopacai.auth.entity.SysUser;
import com.github.wuzhudexiaopacai.auth.mapper.SysUserMapper;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 校验 JWT 是否仍然有效：用户必须 ACTIVE，且令牌版本与库中一致。
 *
 * <p>这样禁用、降级、改密、登出后不必等 access token 自然过期，
 * 最迟 {@code 10s}（缓存 TTL）内旧令牌全部失效。</p>
 */
@Service
@RequiredArgsConstructor
public class TokenSessionValidator {

    private final SysUserMapper userMapper;

    private final Cache<String, Optional<SysUser>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(10))
            .maximumSize(10_000)
            .build();

    public boolean isValid(String username, Integer tokenVersion) {
        if (username == null) {
            return false;
        }
        SysUser user = cache.get(username, name -> Optional.ofNullable(
                        userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                                .eq(SysUser::getUsername, name)
                                .last("limit 1"))))
                .orElse(null);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            return false;
        }
        int current = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        int token = tokenVersion == null ? 0 : tokenVersion;
        return current == token;
    }

    public void invalidate(String username) {
        if (username != null) {
            cache.invalidate(username);
        }
    }
}
