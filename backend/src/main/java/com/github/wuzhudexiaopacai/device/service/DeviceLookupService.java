package com.github.wuzhudexiaopacai.device.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 设备热数据查询：遥测/影子链路高频按 deviceId 查询，使用本地缓存。 */
@Service
@RequiredArgsConstructor
public class DeviceLookupService {

    private final DeviceMapper deviceMapper;

    private final Cache<String, Optional<Device>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(50_000)
            .build();

    public Device getByDeviceId(String deviceId) {
        return cache.get(deviceId, id -> Optional.ofNullable(
                        deviceMapper.selectOne(Wrappers.<Device>lambdaQuery()
                                .eq(Device::getDeviceId, id)
                                .last("limit 1"))))
                .orElse(null);
    }

    public void invalidate(String deviceId) {
        cache.invalidate(deviceId);
    }
}
