package com.github.wuzhudexiaopacai.device.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

class DeviceStatusServiceTest {

    private DeviceMapper deviceMapper;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private SimpleMeterRegistry meterRegistry;
    private DeviceStatusService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // 纯单元测试无 Spring 容器，手动初始化 MyBatis-Plus 表元数据（Lambda 缓存）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Device.class);
        deviceMapper = mock(DeviceMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        meterRegistry = new SimpleMeterRegistry();
        service = new DeviceStatusService(deviceMapper, mock(DeviceLookupService.class),
                redisTemplate, meterRegistry);
        ReflectionTestUtils.setField(service, "onlineTtlSeconds", 180L);
    }

    @Test
    void online_setsStatusAndTtlMarker() {
        when(deviceMapper.update(any(), any())).thenReturn(1);

        service.onStatus("default", "dev-1", "online");

        verify(deviceMapper).update(any(), any());
        verify(valueOperations).set(eq("coherer:status:device:dev-1"), eq("online"),
                eq(Duration.ofSeconds(180)));
    }

    @Test
    void offline_clearsMarkerWithoutTtl() {
        when(deviceMapper.update(any(), any())).thenReturn(1);

        service.onStatus("default", "dev-1", "offline");

        verify(valueOperations).set("coherer:status:device:dev-1", "offline");
    }

    @Test
    void invalidPayload_ignored() {
        service.onStatus("default", "dev-1", "weird");

        verify(deviceMapper, never()).update(any(), any());
        verify(valueOperations, never()).set(anyString(), anyString());
    }

    @Test
    void heartbeat_existingMarker_onlyRefreshesTtl() {
        when(redisTemplate.expire("coherer:status:device:dev-1", Duration.ofSeconds(180)))
                .thenReturn(true);

        service.heartbeat("dev-1");

        verify(deviceMapper, never()).update(any(), any());
        verify(valueOperations, never()).set(anyString(), anyString());
    }

    @Test
    void heartbeat_missingMarker_marksOnline() {
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(false);
        when(deviceMapper.update(any(), any())).thenReturn(1);

        service.heartbeat("dev-1");

        verify(deviceMapper).update(any(), any());
        verify(valueOperations).set(eq("coherer:status:device:dev-1"), eq("online"),
                eq(Duration.ofSeconds(180)));
    }

    @Test
    void heartbeat_redisDown_doesNotThrow() {
        when(redisTemplate.expire(anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.heartbeat("dev-1"));
        verify(deviceMapper, never()).update(any(), any());
    }

    @Test
    void heartbeat_markerWriteFails_stillUpdatesDatabase() {
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(false);
        when(deviceMapper.update(any(), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        // Redis 写失败不能影响设备状态落库，更不能影响遥测主链路
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.heartbeat("dev-1"));
        verify(deviceMapper).update(any(), any());
    }

    @Test
    void sweep_redisDown_doesNotMassMarkOffline() {
        Device device = new Device();
        device.setDeviceId("dev-1");
        when(deviceMapper.selectList(any())).thenReturn(List.of(device));
        when(redisTemplate.hasKey("coherer:status:device:dev-1"))
                .thenThrow(new RuntimeException("redis down"));

        service.sweepStaleOnline();

        verify(deviceMapper, never()).update(any(), any());
    }

    @Test
    void sweep_withoutMarker_marksOffline() {
        Device device = new Device();
        device.setDeviceId("dev-1");
        when(deviceMapper.selectList(any())).thenReturn(List.of(device));
        when(redisTemplate.hasKey("coherer:status:device:dev-1")).thenReturn(false);
        when(deviceMapper.update(any(), any())).thenReturn(1);

        service.sweepStaleOnline();

        verify(deviceMapper).update(any(), any());
        verify(valueOperations).set("coherer:status:device:dev-1", "offline");
    }

    @Test
    void sweep_withLiveMarker_keepsOnline() {
        Device device = new Device();
        device.setDeviceId("dev-1");
        when(deviceMapper.selectList(any())).thenReturn(List.of(device));
        when(redisTemplate.hasKey("coherer:status:device:dev-1")).thenReturn(true);

        service.sweepStaleOnline();

        verify(deviceMapper, never()).update(any(), any());
    }

    @Test
    void sweep_countsOnlyActuallyUpdatedDevices() {
        Device alive = new Device();
        alive.setDeviceId("dev-alive");
        Device stale = new Device();
        stale.setDeviceId("dev-stale");
        when(deviceMapper.selectList(any())).thenReturn(List.of(alive, stale));
        when(redisTemplate.hasKey("coherer:status:device:dev-alive")).thenReturn(true);
        when(redisTemplate.hasKey("coherer:status:device:dev-stale")).thenReturn(false);
        // 巡检与设备上线并发：update 返回 0 表示已被其他路径更新
        when(deviceMapper.update(any(), any())).thenReturn(0);

        service.sweepStaleOnline();

        // 巡检与设备上线并发：update 返回 0 表示已被其他路径更新，指标不计数
        assertEquals(0.0, meterRegistry.counter("coherer.device.status.stale_offline").count());
    }
}
