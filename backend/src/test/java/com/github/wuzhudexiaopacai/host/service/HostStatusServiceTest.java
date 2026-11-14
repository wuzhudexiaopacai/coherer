package com.github.wuzhudexiaopacai.host.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.host.entity.IotHost;
import com.github.wuzhudexiaopacai.host.mapper.IotHostMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HostStatusServiceTest {

    private IotHostMapper hostMapper;
    private DeviceMapper deviceMapper;
    private HostLookupService hostLookup;
    private DeviceLookupService deviceLookup;
    private HostStatusService service;
    private IotHost host;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotHost.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Device.class);
        hostMapper = mock(IotHostMapper.class);
        deviceMapper = mock(DeviceMapper.class);
        hostLookup = mock(HostLookupService.class);
        deviceLookup = mock(DeviceLookupService.class);
        service = new HostStatusService(hostMapper, deviceMapper, hostLookup,
                deviceLookup, new SimpleMeterRegistry());
        host = new IotHost();
        host.setId(1L);
        host.setHostId("host_1");
        host.setTenantId("default");
        host.setStatus("ONLINE");
        when(hostLookup.getByHostId("host_1")).thenReturn(host);
    }

    @Test
    void online_updatesHostOnly() {
        when(hostMapper.update(any(), any())).thenReturn(1);

        service.onStatus("default", "host_1", "online");

        verify(hostLookup).invalidate(host);
        verify(deviceMapper, never()).update(any(), any());
    }

    @Test
    void offline_cascadesChildren() {
        when(hostMapper.update(any(), any())).thenReturn(1);
        Device child = new Device();
        child.setDeviceId("dev-1");
        when(deviceMapper.selectList(any())).thenReturn(List.of(child));
        when(deviceMapper.update(any(), any())).thenReturn(1);

        service.onStatus("default", "host_1", "offline");

        verify(deviceMapper).update(any(), any());
        verify(deviceLookup).invalidate("dev-1");
    }

    @Test
    void tenantMismatch_isIgnored() {
        service.onStatus("other", "host_1", "offline");

        verify(hostMapper, never()).update(any(), any());
    }
}
