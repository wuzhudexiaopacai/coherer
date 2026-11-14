package com.github.wuzhudexiaopacai.host.service;

import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.host.entity.IotHost;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 校验主机是否有权代表某子设备上报：必须同租户且设备已绑定到该主机。 */
@Service
@RequiredArgsConstructor
public class HostDeviceAccessService {

    private final HostLookupService hostLookupService;
    private final DeviceLookupService deviceLookupService;

    public boolean isBound(String hostId, String tenantId, String deviceId) {
        IotHost host = hostLookupService.getByHostId(hostId);
        if (host == null || "DISABLED".equals(host.getStatus())) {
            return false;
        }
        String hostTenant = host.getTenantId() == null ? "default" : host.getTenantId();
        if (!hostTenant.equals(tenantId)) {
            return false;
        }
        Device device = deviceLookupService.getByDeviceId(deviceId);
        return device != null && host.getId().equals(device.getHostId())
                && hostTenant.equals(device.getTenantId() == null ? "default" : device.getTenantId());
    }
}
