package com.github.wuzhudexiaopacai.host.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import java.time.Duration;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

class HostStatusServiceTest {

    private IotHostMapper hostMapper;
    private DeviceMapper deviceMapper;
    private HostLookupService hostLookup;
    private DeviceLookupService deviceLookup;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private SimpleMeterRegistry registry;
    private HostStatusService service;
    private IotHost host;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotHost.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Device.class);
        hostMapper = mock(IotHostMapper.class);
        deviceMapper = mock(DeviceMapper.class);
        hostLookup = mock(HostLookupService.class);
        deviceLookup = mock(DeviceLookupService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        registry = new SimpleMeterRegistry();
        service = new HostStatusService(hostMapper, deviceMapper, hostLookup,
                deviceLookup, redisTemplate, registry);
        ReflectionTestUtils.setField(service, "onlineTtlSeconds", 180L);
        host = new IotHost();
        host.setId(1L);
        host.setHostId("host_1");
        host.setTenantId("default");
        host.setStatus("ONLINE");
        when(hostLookup.getByHostId("host_1")).thenReturn(host);
    }

    @Test
    void online_updatesHostAndTtl() {
        when(hostMapper.update(any(), any())).thenReturn(1);

        service.onStatus("default", "host_1", "online");

        verify(hostLookup).invalidate(host);
        verify(valueOperations).set("coherer:status:host:host_1", "online",
                Duration.ofSeconds(180));
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
        verify(valueOperations).set("coherer:status:host:host_1", "offline");
    }

    @Test
    void tenantMismatch_isIgnored() {
        service.onStatus("other", "host_1", "offline");

        verify(hostMapper, never()).update(any(), any());
    }

    @Test
    void heartbeat_existingMarker_onlyRefreshesTtl() {
        when(valueOperations.get("coherer:status:host:host_1")).thenReturn("online");
        when(redisTemplate.expire("coherer:status:host:host_1", Duration.ofSeconds(180)))
                .thenReturn(true);

        service.heartbeat("host_1");

        verify(hostMapper, never()).update(any(), any());
    }

    @Test
    void heartbeat_missingMarker_marksOnline() {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(hostMapper.update(any(), any())).thenReturn(1);

        service.heartbeat("host_1");

        verify(hostMapper).update(any(), any());
        verify(valueOperations).set("coherer:status:host:host_1", "online",
                Duration.ofSeconds(180));
    }

    @Test
    void sweep_withoutMarker_marksOfflineAndCascades() {
        when(hostMapper.selectList(any())).thenReturn(List.of(host));
        when(valueOperations.get("coherer:status:host:host_1")).thenReturn(null);
        when(hostMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        service.sweepStaleOnline();

        verify(hostMapper).update(any(), any());
        org.junit.jupiter.api.Assertions.assertEquals(1.0,
                registry.counter("coherer.host.status.stale_offline").count());
    }

    @Test
    void sweep_withLiveMarker_keepsOnline() {
        when(hostMapper.selectList(any())).thenReturn(List.of(host));
        when(valueOperations.get("coherer:status:host:host_1")).thenReturn("online");

        service.sweepStaleOnline();

        verify(hostMapper, never()).update(any(), any());
    }

    @Test
    void sweep_redisDown_doesNotMassMarkOffline() {
        when(hostMapper.selectList(any())).thenReturn(List.of(host));
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("redis down"));

        service.sweepStaleOnline();

        verify(hostMapper, never()).update(any(), any());
    }
}
