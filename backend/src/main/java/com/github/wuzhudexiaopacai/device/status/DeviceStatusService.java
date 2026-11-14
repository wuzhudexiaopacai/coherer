package com.github.wuzhudexiaopacai.device.status;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 设备在线状态：设备连接后向 {tenant}/{deviceId}/status 发布 online，
 * 异常断连由 LWT 代发 offline；状态写入 PostgreSQL 与 Redis。
 *
 * <p>兜底机制：在线标记在 Redis 带 TTL，设备任何消息（遥测/影子/状态）都会续期；
 * TTL 过期后由定时巡检把设备置为 OFFLINE，避免 LWT 丢失或后端崩溃导致永久在线。</p>
 */
@Service
@RequiredArgsConstructor
public class DeviceStatusService {

    private static final Logger log = LoggerFactory.getLogger(DeviceStatusService.class);
    private static final String REDIS_KEY_PREFIX = "coherer:status:device:";

    private final DeviceMapper deviceMapper;
    private final DeviceLookupService deviceLookupService;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    /** 在线标记存活时间：超过该时长没有任何设备消息即视为失联。 */
    @Value("${coherer.device.online-ttl-seconds:180}")
    private long onlineTtlSeconds;

    public void onStatus(String tenantId, String deviceId, String payload) {
        String status = payload == null ? "" : payload.trim().toLowerCase();
        boolean online = "online".equals(status);
        if (!online && !"offline".equals(status)) {
            log.debug("忽略非法状态消息 deviceId={} payload={}", deviceId, payload);
            return;
        }

        if (online) {
            markOnline(deviceId);
        } else {
            markOffline(deviceId);
        }
    }

    /**
     * 设备消息存活续期：遥测/影子上报都证明设备在线。
     *
     * <p>Redis 已存在在线标记时只刷 TTL，不写数据库；标记缺失（首次接入或已过期）
     * 才落库，避免每条消息一次 UPDATE。</p>
     */
    public void heartbeat(String deviceId) {
        String key = REDIS_KEY_PREFIX + deviceId;
        Boolean existed = redisTemplate.expire(key, Duration.ofSeconds(onlineTtlSeconds));
        if (Boolean.TRUE.equals(existed)) {
            return;
        }
        markOnline(deviceId);
    }

    private void markOnline(String deviceId) {
        int updated = deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                .eq(Device::getDeviceId, deviceId)
                .ne(Device::getStatus, "DISABLED")
                .ne(Device::getStatus, "ONLINE")
                .set(Device::getStatus, "ONLINE")
                .set(Device::getLastOnlineAt, OffsetDateTime.now()));
        redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + deviceId, "online",
                Duration.ofSeconds(onlineTtlSeconds));
        if (updated > 0) {
            deviceLookupService.invalidate(deviceId);
        }
    }

    private void markOffline(String deviceId) {
        int updated = deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                .eq(Device::getDeviceId, deviceId)
                .eq(Device::getStatus, "ONLINE")
                .set(Device::getStatus, "OFFLINE"));
        redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + deviceId, "offline");
        if (updated > 0) {
            deviceLookupService.invalidate(deviceId);
        }
    }

    /**
     * 定时回收超时在线设备：Redis 在线标记已过期（或丢失）但库中仍为 ONLINE 的设备，
     * 统一置为 OFFLINE。多实例并发执行时 update 条件含 status=ONLINE，天然幂等。
     */
    @Scheduled(fixedDelayString = "${coherer.device.online-sweep-interval-ms:60000}",
            initialDelayString = "${coherer.device.online-sweep-initial-delay-ms:30000}")
    public void sweepStaleOnline() {
        List<Device> onlineDevices;
        try {
            onlineDevices = deviceMapper.selectList(Wrappers.<Device>lambdaQuery()
                    .select(Device::getDeviceId)
                    .eq(Device::getStatus, "ONLINE"));
        } catch (Exception e) {
            log.warn("在线状态巡检查询失败: {}", e.getMessage());
            return;
        }
        if (onlineDevices.isEmpty()) {
            return;
        }

        int marked = 0;
        for (Device device : onlineDevices) {
            String deviceId = device.getDeviceId();
            Boolean alive = redisTemplate.hasKey(REDIS_KEY_PREFIX + deviceId);
            if (Boolean.TRUE.equals(alive)) {
                continue;
            }
            int updated = deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                    .eq(Device::getDeviceId, deviceId)
                    .eq(Device::getStatus, "ONLINE")
                    .set(Device::getStatus, "OFFLINE"));
            if (updated > 0) {
                marked++;
                redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + deviceId, "offline");
                deviceLookupService.invalidate(deviceId);
            }
        }
        if (marked > 0) {
            meterRegistry.counter("coherer.device.status.stale_offline").increment(marked);
            log.info("在线状态巡检：{} 台设备因心跳超时置为 OFFLINE", marked);
        }
    }
}
