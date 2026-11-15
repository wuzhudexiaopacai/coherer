package com.github.wuzhudexiaopacai.ota.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.host.entity.IotHost;
import com.github.wuzhudexiaopacai.host.service.HostLookupService;
import com.github.wuzhudexiaopacai.ota.dto.OtaStartRequest;
import com.github.wuzhudexiaopacai.ota.entity.Firmware;
import com.github.wuzhudexiaopacai.ota.entity.OtaTask;
import com.github.wuzhudexiaopacai.ota.mapper.FirmwareMapper;
import com.github.wuzhudexiaopacai.ota.mapper.OtaTaskMapper;
import com.github.wuzhudexiaopacai.security.DataScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class OtaServiceTest {

    private FirmwareService firmwareService;
    private FirmwareMapper firmwareMapper;
    private DeviceLookupService deviceLookupService;
    private HostLookupService hostLookupService;
    private OtaPublisher publisher;
    private ApplicationEventPublisher eventPublisher;
    private OtaTaskMapper taskMapper;
    private SimpleMeterRegistry registry;
    private OtaService service;
    private Firmware firmware;
    private Device device;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OtaTask.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Firmware.class);
        firmwareService = mock(FirmwareService.class);
        firmwareMapper = mock(FirmwareMapper.class);
        deviceLookupService = mock(DeviceLookupService.class);
        hostLookupService = mock(HostLookupService.class);
        publisher = mock(OtaPublisher.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        taskMapper = mock(OtaTaskMapper.class);
        registry = new SimpleMeterRegistry();
        service = new OtaService(firmwareService, firmwareMapper, deviceLookupService,
                hostLookupService, publisher, eventPublisher, registry);
        ReflectionTestUtils.setField(service, "baseMapper", taskMapper);
        ReflectionTestUtils.setField(service, "taskTimeoutSeconds", 600L);

        firmware = new Firmware();
        firmware.setId(10L);
        firmware.setProductId(1L);
        firmware.setVersion("1.2.0");
        firmware.setSizeBytes(128L);
        firmware.setSha256("a".repeat(64));
        firmware.setFileName("fw.bin");
        when(firmwareService.require(10L)).thenReturn(firmware);
        when(firmwareService.downloadUrl(firmware, "dev-1")).thenReturn("https://dl/signed");

        device = new Device();
        device.setId(5L);
        device.setDeviceId("dev-1");
        device.setProductId(1L);
        device.setSpaceId(100L);
        device.setTenantId("default");
        device.setStatus("ONLINE");
        when(deviceLookupService.getByDeviceId("dev-1")).thenReturn(device);
        when(taskMapper.insert(any(OtaTask.class))).thenAnswer(invocation -> {
            ((OtaTask) invocation.getArgument(0)).setId(88L);
            return 1;
        });
        when(taskMapper.update(any(), any())).thenReturn(1);
    }

    @Test
    void start_directDevice_publishesAndMarksSent() {
        when(publisher.publishOta(eq("default"), eq("dev-1"), isNull(), eq(88L), eq(10L),
                eq("1.2.0"), eq("https://dl/signed"), eq("a".repeat(64)), eq(128L)))
                .thenReturn(true);

        var vo = service.start(request(), scope(100L), "admin");

        assertEquals("SENT", vo.getStatus());
        assertEquals("1.2.0", vo.getFirmwareVersion());
        assertEquals(88L, vo.getId());
    }

    @Test
    void start_hostedDevice_routesThroughHost() {
        device.setHostId(7L);
        IotHost host = new IotHost();
        host.setHostId("host_7");
        host.setStatus("ONLINE");
        when(hostLookupService.getById(7L)).thenReturn(host);
        when(publisher.publishOta(any(), any(), eq("host_7"), any(), any(), any(), any(), any(),
                anyLong())).thenReturn(true);

        var vo = service.start(request(), scope(100L), "admin");

        assertEquals("SENT", vo.getStatus());
        verify(publisher).publishOta("default", "dev-1", "host_7", 88L, 10L, "1.2.0",
                "https://dl/signed", "a".repeat(64), 128L);
    }

    @Test
    void start_hostUnavailable_marksFailed() {
        device.setHostId(7L);
        when(hostLookupService.getById(7L)).thenReturn(null);

        var vo = service.start(request(), scope(100L), "admin");

        assertEquals("FAILED", vo.getStatus());
        assertEquals("设备所属主机不可用", vo.getError());
        verify(publisher, never()).publishOta(any(), any(), any(), any(), any(), any(), any(), any(),
                anyLong());
    }

    @Test
    void start_productMismatch_isRejected() {
        device.setProductId(2L);

        BizException exception = assertThrows(BizException.class,
                () -> service.start(request(), scope(100L), "admin"));
        assertEquals(409, exception.getCode());
    }

    @Test
    void start_disabledDevice_isRejected() {
        device.setStatus("DISABLED");

        BizException exception = assertThrows(BizException.class,
                () -> service.start(request(), scope(100L), "admin"));
        assertEquals(409, exception.getCode());
    }

    @Test
    void start_outsideScope_isRejected() {
        BizException exception = assertThrows(BizException.class,
                () -> service.start(request(), DataScope.of(200L, List.of(200L)), "admin"));
        assertEquals(403, exception.getCode());
    }

    @Test
    void start_existingActiveTask_isRejected() {
        when(taskMapper.selectCount(any())).thenReturn(1L);

        BizException exception = assertThrows(BizException.class,
                () -> service.start(request(), scope(100L), "admin"));
        assertEquals(409, exception.getCode());
        verify(taskMapper, never()).insert(any(OtaTask.class));
    }

    @Test
    void start_mqttUnavailable_marksFailed() {
        when(publisher.publishOta(any(), any(), any(), any(), any(), any(), any(), any(),
                anyLong())).thenReturn(false);

        var vo = service.start(request(), scope(100L), "admin");

        assertEquals("FAILED", vo.getStatus());
        assertEquals("MQTT 通道未连接，OTA 指令未下发", vo.getError());
    }

    @Test
    void start_unknownDevice_isRejected() {
        when(deviceLookupService.getByDeviceId("dev-1")).thenReturn(null);

        BizException exception = assertThrows(BizException.class,
                () -> service.start(request(), scope(100L), "admin"));
        assertEquals(404, exception.getCode());
    }

    @Test
    void recent_returnsFirmwareVersion() {
        OtaTask task = new OtaTask();
        task.setId(1L);
        task.setDeviceId("dev-1");
        task.setFirmwareId(10L);
        task.setStatus("SUCCESS");
        task.setProgress(100);
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(firmwareMapper.selectBatchIds(any())).thenReturn(List.of(firmware));

        var list = service.recent("dev-1", 20, scope(100L));

        assertEquals(1, list.size());
        assertEquals("1.2.0", list.get(0).getFirmwareVersion());
        assertNull(list.get(0).getError());
    }

    @Test
    void recent_empty_returnsEmptyWithoutBatchQuery() {
        when(taskMapper.selectList(any())).thenReturn(List.of());

        var list = service.recent("dev-1", 20, scope(100L));

        assertEquals(0, list.size());
        verify(firmwareMapper, never()).selectBatchIds(any());
    }

    @Test
    void recent_outsideScope_isRejected() {
        BizException exception = assertThrows(BizException.class,
                () -> service.recent("dev-1", 20, DataScope.of(200L, List.of(200L))));
        assertEquals(403, exception.getCode());
    }

    private OtaStartRequest request() {
        OtaStartRequest request = new OtaStartRequest();
        request.setFirmwareId(10L);
        request.setDeviceId("dev-1");
        return request;
    }

    private DataScope scope(Long spaceId) {
        return DataScope.of(spaceId, List.of(spaceId));
    }
}
