package com.github.wuzhudexiaopacai.alarm.service;

import com.github.wuzhudexiaopacai.alarm.entity.AlarmRecord;
import com.github.wuzhudexiaopacai.alarm.entity.AlarmRule;
import com.github.wuzhudexiaopacai.device.entity.Device;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 告警运行时状态（Redis）：持续计时 + 活动告警认领。
 *
 * <p>多实例部署时 EMQX 共享订阅不保证同一设备的消息落到同一实例，因此
 * 「条件成立多久」与「是否已有活动告警」必须放在共享存储里：
 * </p>
 * <ul>
 *   <li>{@code coherer:alarm:since:{ruleId}:{deviceId}} → 条件首次成立时间</li>
 *   <li>{@code coherer:alarm:active:{ruleId}:{deviceId}} → 活动告警认领标记</li>
 * </ul>
 *
 * <p>Redis 不可用时降级为「放行」：由数据库 hasActive 兜底，宁可重复评估也不漏告警。</p>
 */
@Service
public class AlarmStateService {

    private static final Logger log = LoggerFactory.getLogger(AlarmStateService.class);
    private static final String SINCE_PREFIX = "coherer:alarm:since:";
    private static final String ACTIVE_PREFIX = "coherer:alarm:active:";

    private final StringRedisTemplate redisTemplate;
    private final long conditionTtlSeconds;
    private final long activeTtlSeconds;

    public AlarmStateService(
            StringRedisTemplate redisTemplate,
            @Value("${coherer.alarm.condition-ttl-seconds:3600}") long conditionTtlSeconds,
            @Value("${coherer.alarm.active-ttl-seconds:86400}") long activeTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.conditionTtlSeconds = conditionTtlSeconds;
        this.activeTtlSeconds = activeTtlSeconds;
    }

    /**
     * 记录并返回条件首次成立时间。
     *
     * @return epoch millis；Redis 不可用时返回当前时间（等价于重新计时）
     */
    public long conditionSince(Long ruleId, String deviceId, long now) {
        String key = SINCE_PREFIX + ruleId + ":" + deviceId;
        try {
            Boolean created = redisTemplate.opsForValue()
                    .setIfAbsent(key, String.valueOf(now),
                            Duration.ofSeconds(conditionTtlSeconds));
            if (Boolean.TRUE.equals(created)) {
                return now;
            }
            String existing = redisTemplate.opsForValue().get(key);
            return existing == null ? now : Long.parseLong(existing);
        } catch (Exception e) {
            log.warn("告警持续计时读写失败（按当前时间处理）: {}", e.getMessage());
            return now;
        }
    }

    public void clearCondition(Long ruleId, String deviceId) {
        try {
            redisTemplate.delete(SINCE_PREFIX + ruleId + ":" + deviceId);
        } catch (Exception e) {
            log.debug("清除告警持续计时失败: {}", e.getMessage());
        }
    }

    /**
     * 认领活动告警：只有第一个成功的实例会创建告警记录。
     *
     * @return true 表示本实例获得认领权（仍需与数据库对齐后决定是否插入）
     */
    public boolean claimActive(Long ruleId, String deviceId) {
        try {
            Boolean created = redisTemplate.opsForValue().setIfAbsent(
                    ACTIVE_PREFIX + ruleId + ":" + deviceId, "1",
                    Duration.ofSeconds(activeTtlSeconds));
            return Boolean.TRUE.equals(created);
        } catch (Exception e) {
            log.warn("活动告警认领失败（降级为数据库判断）: {}", e.getMessage());
            return true;
        }
    }

    public boolean isActive(Long ruleId, String deviceId) {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.hasKey(ACTIVE_PREFIX + ruleId + ":" + deviceId));
        } catch (Exception e) {
            return false;
        }
    }

    /** 告警恢复或关闭时释放认领，允许下次再次触发。 */
    public void releaseActive(Long ruleId, String deviceId) {
        try {
            redisTemplate.delete(ACTIVE_PREFIX + ruleId + ":" + deviceId);
        } catch (Exception e) {
            log.debug("释放活动告警认领失败: {}", e.getMessage());
        }
    }

    /** 预热：启动/重建缓存时按数据库中的活动告警重建认领标记。 */
    public void warmUp(List<AlarmRecord> activeRecords) {
        for (AlarmRecord record : activeRecords) {
            try {
                redisTemplate.opsForValue().set(
                        ACTIVE_PREFIX + record.getRuleId() + ":" + record.getDeviceId(), "1",
                        Duration.ofSeconds(activeTtlSeconds));
            } catch (Exception e) {
                log.warn("活动告警预热失败 id={} err={}", record.getId(), e.getMessage());
                return;
            }
        }
    }

    static String activeKey(AlarmRule rule, Device device) {
        return ACTIVE_PREFIX + rule.getId() + ":" + device.getDeviceId();
    }
}
