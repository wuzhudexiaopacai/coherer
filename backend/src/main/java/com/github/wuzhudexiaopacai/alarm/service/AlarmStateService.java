package com.github.wuzhudexiaopacai.alarm.service;

import com.github.wuzhudexiaopacai.alarm.entity.AlarmRecord;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 告警运行时状态（Redis）：
 *
 * <ul>
 *   <li>{@code coherer:alarm:since:{ruleId}:{deviceId}} → 条件首次成立时间</li>
 *   <li>{@code coherer:alarm:active:dev:{deviceId}} → 该设备活动 ruleId 集合</li>
 * </ul>
 *
 * <p>活动集合按设备聚合，评估一条消息只需一次 SMEMBERS，而不是每条规则一次查询。
 * Redis 不可用时 {@link #activeRuleIds} 返回 empty Optional，由调用方回退数据库批量查询。</p>
 */
@Service
public class AlarmStateService {

    private static final Logger log = LoggerFactory.getLogger(AlarmStateService.class);
    private static final String SINCE_PREFIX = "coherer:alarm:since:";
    private static final String DEVICE_ACTIVE_PREFIX = "coherer:alarm:active:dev:";

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
     * 一次取出设备当前所有活动规则。
     *
     * @return empty 表示 Redis 不可用；空集合表示确实没有活动告警
     */
    public Optional<Set<Long>> activeRuleIds(String deviceId) {
        try {
            Set<String> members = redisTemplate.opsForSet()
                    .members(DEVICE_ACTIVE_PREFIX + deviceId);
            if (members == null || members.isEmpty()) {
                return Optional.of(Set.of());
            }
            return Optional.of(members.stream().map(Long::valueOf).collect(Collectors.toSet()));
        } catch (Exception e) {
            log.warn("读取设备活动告警失败（回退数据库） deviceId={} err={}",
                    deviceId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 认领活动告警：SADD 返回 1 的实例获得触发权。
     * Redis 不可用时放行，由数据库活动告警兜底。
     */
    public boolean claimActive(Long ruleId, String deviceId) {
        String key = DEVICE_ACTIVE_PREFIX + deviceId;
        try {
            Long added = redisTemplate.opsForSet().add(key, String.valueOf(ruleId));
            redisTemplate.expire(key, Duration.ofSeconds(activeTtlSeconds));
            return added != null && added > 0;
        } catch (Exception e) {
            log.warn("活动告警认领失败（降级放行）: {}", e.getMessage());
            return true;
        }
    }

    /** 告警恢复/关闭时释放认领，允许下次再次触发。 */
    public void releaseActive(Long ruleId, String deviceId) {
        try {
            redisTemplate.opsForSet()
                    .remove(DEVICE_ACTIVE_PREFIX + deviceId, String.valueOf(ruleId));
        } catch (Exception e) {
            log.debug("释放活动告警认领失败: {}", e.getMessage());
        }
    }

    /** 按数据库活动告警回填某设备的 Redis 集合。 */
    public void warmUpDevice(String deviceId, Collection<Long> ruleIds) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            return;
        }
        String key = DEVICE_ACTIVE_PREFIX + deviceId;
        try {
            String[] members = ruleIds.stream().map(String::valueOf).toArray(String[]::new);
            redisTemplate.opsForSet().add(key, members);
            redisTemplate.expire(key, Duration.ofSeconds(activeTtlSeconds));
        } catch (Exception e) {
            log.warn("活动告警预热失败 deviceId={} err={}", deviceId, e.getMessage());
        }
    }

    /** 启动时按数据库活动告警重建 Redis 集合。 */
    public void warmUp(List<AlarmRecord> activeRecords) {
        Map<String, Set<Long>> byDevice = new HashMap<>();
        for (AlarmRecord record : activeRecords) {
            byDevice.computeIfAbsent(record.getDeviceId(), ignored -> new HashSet<>())
                    .add(record.getRuleId());
        }
        byDevice.forEach(this::warmUpDevice);
        log.info("告警状态预热完成，设备 {} 台", byDevice.size());
    }
}
