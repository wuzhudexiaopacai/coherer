package com.github.wuzhudexiaopacai.host.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.host.entity.IotHost;
import com.github.wuzhudexiaopacai.host.mapper.IotHostMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 主机在线状态与级联：
 * 主机离线时，其下所有 ONLINE 子设备统一置为 OFFLINE；
 * 主机上线不会自动把子设备置在线（子设备仍需各自上报）。
 */
@Service
@RequiredArgsConstructor
public class HostStatusService {

    private static final Logger log = LoggerFactory.getLogger(HostStatusService.class);

    private final IotHostMapper hostMapper;
    private final DeviceMapper deviceMapper;
    private final HostLookupService hostLookupService;
    private final DeviceLookupService deviceLookupService;
    private final MeterRegistry meterRegistry;

    public void onStatus(String tenantId, String hostId, String payload) {
        String status = payload == null ? "" : payload.trim().toLowerCase();
        boolean online = "online".equals(status);
        if (!online && !"offline".equals(status)) {
            log.debug("忽略非法主机状态 hostId={} payload={}", hostId, payload);
            return;
        }
        IotHost host = hostLookupService.getByHostId(hostId);
        if (host == null) {
            log.debug("收到未注册主机的状态 hostId={}", hostId);
            return;
        }
        String tenant = host.getTenantId() == null ? "default" : host.getTenantId();
        if (!tenant.equals(tenantId)) {
            log.warn("主机状态租户不匹配 hostId={} topicTenant={}", hostId, tenantId);
            return;
        }

        if (online) {
            int updated = hostMapper.update(null, Wrappers.<IotHost>lambdaUpdate()
                    .eq(IotHost::getHostId, hostId)
                    .ne(IotHost::getStatus, "DISABLED")
                    .set(IotHost::getStatus, "ONLINE")
                    .set(IotHost::getLastOnlineAt, OffsetDateTime.now()));
            if (updated > 0) {
                hostLookupService.invalidate(host);
            }
            return;
        }

        int updated = hostMapper.update(null, Wrappers.<IotHost>lambdaUpdate()
                .eq(IotHost::getHostId, hostId)
                .eq(IotHost::getStatus, "ONLINE")
                .set(IotHost::getStatus, "OFFLINE"));
        if (updated > 0) {
            hostLookupService.invalidate(host);
        }
        cascadeChildrenOffline(host);
    }

    private void cascadeChildrenOffline(IotHost host) {
        List<Device> children = deviceMapper.selectList(Wrappers.<Device>lambdaQuery()
                .select(Device::getDeviceId)
                .eq(Device::getHostId, host.getId())
                .eq(Device::getStatus, "ONLINE"));
        if (children.isEmpty()) {
            return;
        }
        int offline = deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                .eq(Device::getHostId, host.getId())
                .eq(Device::getStatus, "ONLINE")
                .set(Device::getStatus, "OFFLINE"));
        children.forEach(device -> deviceLookupService.invalidate(device.getDeviceId()));
        meterRegistry.counter("coherer.host.children_offline").increment(offline);
        log.info("主机离线级联：hostId={} 子设备 {} 台置为 OFFLINE", host.getHostId(), offline);
    }
}
