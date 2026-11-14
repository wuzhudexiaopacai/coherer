package com.github.wuzhudexiaopacai.host.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.host.entity.IotHost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HostDeviceAccessServiceTest {

    private HostLookupService hostLookup;
    private DeviceLookupService deviceLookup;
    private HostDeviceAccessService service;

    @BeforeEach
    void setUp() {
        hostLookup = mock(HostLookupService.class);
        deviceLookup = mock(DeviceLookupService.class);
        service = new HostDeviceAccessService(hostLookup, deviceLookup);
    }

    @Test
    void boundChild_isAllowed() {
        when(hostLookup.getByHostId("host_1")).thenReturn(host("ONLINE"));
        when(deviceLookup.getByDeviceId("dev-1")).thenReturn(device(1L, "default"));

        assertTrue(service.isBound("host_1", "default", "dev-1"));
    }

    @Test
    void unboundChild_isRejected() {
        when(hostLookup.getByHostId("host_1")).thenReturn(host("ONLINE"));
        when(deviceLookup.getByDeviceId("dev-1")).thenReturn(device(2L, "default"));

        assertFalse(service.isBound("host_1", "default", "dev-1"));
    }

    @Test
    void tenantMismatch_isRejected() {
        when(hostLookup.getByHostId("host_1")).thenReturn(host("ONLINE"));
        when(deviceLookup.getByDeviceId("dev-1")).thenReturn(device(1L, "other"));

        assertFalse(service.isBound("host_1", "default", "dev-1"));
    }

    @Test
    void disabledHost_isRejected() {
        when(hostLookup.getByHostId("host_1")).thenReturn(host("DISABLED"));

        assertFalse(service.isBound("host_1", "default", "dev-1"));
    }

    private IotHost host(String status) {
        IotHost host = new IotHost();
        host.setId(1L);
        host.setHostId("host_1");
        host.setTenantId("default");
        host.setStatus(status);
        return host;
    }

    private Device device(Long hostId, String tenantId) {
        Device device = new Device();
        device.setDeviceId("dev-1");
        device.setHostId(hostId);
        device.setTenantId(tenantId);
        return device;
    }
}
