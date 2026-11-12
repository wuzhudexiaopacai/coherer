package com.github.wuzhudexiaopacai.device.status;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 设备在线状态：设备连接后向 {tenant}/{deviceId}/status 发布 online，
 * 异常断连由 LWT 代发 offline；状态写入 PostgreSQL 与 Redis。
 */
@Service
@RequiredArgsConstructor
public class DeviceStatusService {

    private static final Logger log = LoggerFactory.getLogger(DeviceStatusService.class);
    private static final String REDIS_KEY_PREFIX = "coherer:status:device:";

    private final DeviceMapper deviceMapper;
    private final DeviceLookupService deviceLookupService;
    private final StringRedisTemplate redisTemplate;

    public void onStatus(String tenantId, String deviceId, String payload) {
        String status = payload == null ? "" : payload.trim().toLowerCase();
        boolean online = "online".equals(status);
        if (!online && !"offline".equals(status)) {
            log.debug("忽略非法状态消息 deviceId={} payload={}", deviceId, payload);
            return;
        }

        if (online) {
            deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                    .eq(Device::getDeviceId, deviceId)
                    .ne(Device::getStatus, "DISABLED")
                    .set(Device::getStatus, "ONLINE")
                    .set(Device::getLastOnlineAt, OffsetDateTime.now()));
        } else {
            deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                    .eq(Device::getDeviceId, deviceId)
                    .eq(Device::getStatus, "ONLINE")
                    .set(Device::getStatus, "OFFLINE"));
        }

        redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + deviceId, online ? "online" : "offline");
        deviceLookupService.invalidate(deviceId);
    }
}
