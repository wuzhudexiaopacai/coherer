package com.github.wuzhudexiaopacai.security;

import com.github.wuzhudexiaopacai.common.BizException;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Refresh Token 一次性存储：每个 refresh token 带 jti，
 * 刷新时原子 GET+DEL，旧 token 立即失效，避免被重放。
 *
 * <p>Redis 不可用时刷新链路失败（503），不做降级放行；访问令牌仍可继续使用到过期。</p>
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);
    private static final String KEY_PREFIX = "coherer:refresh:";

    private final StringRedisTemplate redisTemplate;

    /** 注册一个新的 refresh jti，返回 jti 供写入 JWT。 */
    public String issue(String username, long ttlSeconds) {
        String jti = UUID.randomUUID().toString().replace("-", "");
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + jti, username,
                    Duration.ofSeconds(Math.max(ttlSeconds, 60)));
            return jti;
        } catch (Exception e) {
            log.error("Refresh Token 存储失败: {}", e.getMessage());
            throw new BizException(503, "登录服务暂不可用，请稍后重试");
        }
    }

    /**
     * 消费 jti：存在且属于该用户时删除并返回 true；否则返回 false（重放/已过期）。
     */
    public boolean consume(String jti, String username) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        try {
            String stored = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + jti);
            return username.equals(stored);
        } catch (Exception e) {
            log.warn("Refresh Token 消费失败: {}", e.getMessage());
            throw new BizException(503, "刷新服务暂不可用，请稍后重试");
        }
    }
}
