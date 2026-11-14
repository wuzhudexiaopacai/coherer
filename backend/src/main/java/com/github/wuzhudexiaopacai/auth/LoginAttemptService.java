package com.github.wuzhudexiaopacai.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.stereotype.Service;

/**
 * 登录失败限流（单实例内存实现）：同一用户名 + IP 在 5 分钟内失败 5 次后拒绝登录。
 * 多实例部署时应迁移到 Redis（M3）。
 */
@Service
public class LoginAttemptService {

    static final int MAX_ATTEMPTS = 5;

    private final Cache<String, Integer> attempts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(20_000)
            .build();

    public boolean isBlocked(String username, String clientIp) {
        Integer count = attempts.getIfPresent(key(username, clientIp));
        return count != null && count >= MAX_ATTEMPTS;
    }

    public void recordFailure(String username, String clientIp) {
        attempts.asMap().merge(key(username, clientIp), 1, Integer::sum);
    }

    public void reset(String username, String clientIp) {
        attempts.invalidate(key(username, clientIp));
    }

    private String key(String username, String clientIp) {
        return (username == null ? "" : username) + "|" + (clientIp == null ? "" : clientIp);
    }
}
