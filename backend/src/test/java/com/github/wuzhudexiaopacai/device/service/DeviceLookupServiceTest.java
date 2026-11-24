package com.github.wuzhudexiaopacai.device.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeviceLookupServiceTest {

    private DeviceMapper deviceMapper;
    private DeviceLookupService service;

    @BeforeEach
    void setUp() {
        deviceMapper = mock(DeviceMapper.class);
        service = new DeviceLookupService(deviceMapper);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void sameTenant_hitsCacheAndReturnsDevice() {
        when(deviceMapper.selectOne(any())).thenReturn(device("dev-1", "acme"));
        TenantContext.set("acme");

        assertThat(service.getByDeviceId("dev-1").getDeviceId()).isEqualTo("dev-1");
        assertThat(service.getByDeviceId("dev-1").getDeviceId()).isEqualTo("dev-1");

        verify(deviceMapper, times(1)).selectOne(any());
    }

    @Test
    void cachedDeviceFromOtherTenant_isNotVisible() {
        // 模拟：租户 A 的请求已把设备行放入本地缓存
        when(deviceMapper.selectOne(any())).thenReturn(device("dev-1", "tenant-a"));
        TenantContext.set("tenant-a");
        assertThat(service.getByDeviceId("dev-1")).isNotNull();

        // 租户 B 命中同一缓存行，必须按“设备不存在”处理
        TenantContext.set("tenant-b");
        assertThat(service.getByDeviceId("dev-1")).isNull();
        verify(deviceMapper, times(1)).selectOne(any());
    }

    @Test
    void bypass_allowsCrossTenantLookupForSystemPaths() {
        when(deviceMapper.selectOne(any())).thenReturn(device("dev-1", "tenant-a"));

        Device device = TenantContext.callBypass(() -> service.getByDeviceId("dev-1"));

        assertThat(device).isNotNull();
        assertThat(device.getTenantId()).isEqualTo("tenant-a");
    }

    @Test
    void noContext_failsClosed() {
        when(deviceMapper.selectOne(any())).thenReturn(device("dev-1", "tenant-a"));

        assertThatThrownBy(() -> service.getByDeviceId("dev-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租户上下文");
    }

    @Test
    void missingDevice_returnsNull() {
        when(deviceMapper.selectOne(any())).thenReturn(null);
        TenantContext.set("tenant-a");

        assertThat(service.getByDeviceId("ghost")).isNull();
    }

    private Device device(String deviceId, String tenantId) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setTenantId(tenantId);
        device.setSpaceId(10L);
        return device;
    }
}
