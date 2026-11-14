package com.github.wuzhudexiaopacai.telemetry.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 遥测幂等：同一设备在 TTL 窗口内重复投递（QoS1 重传 / 共享订阅重平衡 / 设备重发）
 * 只处理一次。
 *
 * <p>Redis 不可用时采取 fail-open：记录告警并放行，优先保证数据不丢，
 * 此时重复消息可能重复入库，由 Grafana 指标
 * {@code coherer.telemetry.idempotency.degraded} 暴露。</p>
 */
@Service
public class TelemetryIdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIdempotencyService.class);
    private static final String KEY_PREFIX = "coherer:telemetry:dedup:";

    private final StringRedisTemplate redisTemplate;
    private final long ttlSeconds;

    public TelemetryIdempotencyService(
            StringRedisTemplate redisTemplate,
            @Value("${coherer.telemetry.dedup-ttl-seconds:600}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * 计算幂等键：有消息 id 时用 id；否则用 ts + 参数指纹（参数按 key 排序后哈希）。
     */
    public String key(String deviceId, String messageId, long ts, Map<String, Object> params) {
        String body = messageId != null && !messageId.isBlank()
                ? "id:" + messageId
                : "ts:" + ts + "|" + fingerprint(params);
        return KEY_PREFIX + deviceId + ":" + sha256(body);
    }

    /**
     * 标记为已处理。
     *
     * @return true 表示首次出现（可继续处理）；false 表示重复
     */
    public boolean markIfNew(String key) {
        try {
            Boolean created = redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", Duration.ofSeconds(ttlSeconds));
            return Boolean.TRUE.equals(created);
        } catch (Exception e) {
            log.warn("幂等键写入失败（放行处理）: {}", e.getMessage());
            return true;
        }
    }

    /** 下游处理失败时释放幂等键，允许 MQTT 重投后重新处理。 */
    public void release(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.debug("幂等键释放失败: {}", e.getMessage());
        }
    }

    private String fingerprint(Map<String, Object> params) {
        return sha256(new TreeMap<>(params).toString());
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算遥测幂等键失败", e);
        }
    }
}
