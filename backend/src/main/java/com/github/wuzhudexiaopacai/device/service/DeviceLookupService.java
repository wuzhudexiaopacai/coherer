package com.github.wuzhudexiaopacai.device.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.security.TenantContext;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 设备热数据查询：遥测/影子链路高频按 deviceId 查询，使用本地缓存。
 *
 * <p>缓存键只有 deviceId，命中时不会执行 SQL，因此 MyBatis 租户拦截器不会生效。
 * 读取后必须显式校验设备租户与当前上下文一致，否则跨租户请求会命中其他租户的缓存行。</p>
 */
@Service
@RequiredArgsConstructor
public class DeviceLookupService {

    private static final Logger log = LoggerFactory.getLogger(DeviceLookupService.class);

    private final DeviceMapper deviceMapper;

    private final Cache<String, Optional<Device>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(50_000)
            .build();

    public Device getByDeviceId(String deviceId) {
        Device device = cache.get(deviceId, id -> Optional.ofNullable(
                        deviceMapper.selectOne(Wrappers.<Device>lambdaQuery()
                                .eq(Device::getDeviceId, id)
                                .last("limit 1"))))
                .orElse(null);
        return visible(device);
    }

    /**
     * 缓存命中路径的租户边界：系统任务必须显式 bypass 才能跨租户读取。
     * 跨租户一律按“不存在”处理，避免通过缓存探测其他租户的设备。
     */
    private Device visible(Device device) {
        if (device == null || TenantContext.bypassed()) {
            return device;
        }
        String tenant = TenantContext.requireTenant();
        if (!tenant.equals(device.getTenantId())) {
            log.debug("拒绝跨租户设备缓存命中 deviceId={} deviceTenant={} requestTenant={}",
                    device.getDeviceId(), device.getTenantId(), tenant);
            return null;
        }
        return device;
    }

    public void invalidate(String deviceId) {
        cache.invalidate(deviceId);
    }
}
