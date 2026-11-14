package com.github.wuzhudexiaopacai.command.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.github.wuzhudexiaopacai.command.dto.CommandSendRequest;
import com.github.wuzhudexiaopacai.command.entity.DeviceCommand;
import com.github.wuzhudexiaopacai.command.mapper.DeviceCommandMapper;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class CommandServiceTest {

    private DeviceCommandMapper mapper;
    private CommandPublisher publisher;
    private DeviceLookupService deviceLookupService;
    private CommandService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), DeviceCommand.class);
        mapper = mock(DeviceCommandMapper.class);
        publisher = mock(CommandPublisher.class);
        deviceLookupService = mock(DeviceLookupService.class);
        service = new CommandService(publisher, deviceLookupService, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "replyTimeoutSeconds", 30L);
        when(mapper.insert((DeviceCommand) any())).thenAnswer(invocation -> {
            // 模拟 MyBatis-Plus 雪花 ID 回填
            ((DeviceCommand) invocation.getArgument(0)).setId(1L);
            return 1;
        });
        when(mapper.update(any(), any())).thenReturn(1);
    }

    @Test
    void send_publishesAndMarksSent() {
        when(deviceLookupService.getByDeviceId("dev-1")).thenReturn(device("ONLINE"));
        when(publisher.publishCommand(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);
        CommandSendRequest request = new CommandSendRequest();
        request.setIdentifier("setInterval");
        request.setParams(Map.of("seconds", 5));

        var vo = service.send("dev-1", request, "admin");

        assertEquals("SENT", vo.getStatus());
        assertTrue(vo.getRequestId().length() >= 32);
        verify(publisher).publishCommand(eq("default"), eq("dev-1"), eq("setInterval"),
                eq(vo.getRequestId()), eq(Map.of("seconds", 5)));
        // 条件更新 PENDING -> SENT，避免覆盖抢先到达的回执
        verify(mapper).update(any(), any());
    }

    @Test
    void send_whenMqttDown_marksFailed() {
        when(deviceLookupService.getByDeviceId("dev-1")).thenReturn(device("ONLINE"));
        when(publisher.publishCommand(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(false);
        CommandSendRequest request = new CommandSendRequest();
        request.setIdentifier("reboot");

        var vo = service.send("dev-1", request, "admin");

        assertEquals("FAILED", vo.getStatus());
        assertTrue(vo.getError().contains("MQTT"));
    }

    @Test
    void send_unknownDevice_throws404() {
        when(deviceLookupService.getByDeviceId("ghost")).thenReturn(null);
        CommandSendRequest request = new CommandSendRequest();
        request.setIdentifier("reboot");

        BizException exception = assertThrows(BizException.class,
                () -> service.send("ghost", request, "admin"));
        assertEquals(404, exception.getCode());
    }

    @Test
    void send_disabledDevice_throws409() {
        when(deviceLookupService.getByDeviceId("dev-1")).thenReturn(device("DISABLED"));
        CommandSendRequest request = new CommandSendRequest();
        request.setIdentifier("reboot");

        BizException exception = assertThrows(BizException.class,
                () -> service.send("dev-1", request, "admin"));
        assertEquals(409, exception.getCode());
        verify(publisher, never()).publishCommand(any(), any(), any(), any(), any());
    }

    @Test
    void send_replyArrivesBeforeStatusUpdate_keepsSuccess() {
        when(deviceLookupService.getByDeviceId("dev-1")).thenReturn(device("ONLINE"));
        when(publisher.publishCommand(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);
        // 回执先到：条件更新影响 0 行，随后以数据库最新状态为准
        when(mapper.update(any(), any())).thenReturn(0);
        DeviceCommand replied = new DeviceCommand();
        replied.setId(1L);
        replied.setStatus("SUCCESS");
        when(mapper.selectById(1L)).thenReturn(replied);
        CommandSendRequest request = new CommandSendRequest();
        request.setIdentifier("reboot");

        var vo = service.send("dev-1", request, "admin");

        assertEquals("SUCCESS", vo.getStatus());
    }

    @Test
    void sweepTimeouts_coversSentAndPending() {
        when(mapper.update(any(), any())).thenReturn(2, 1);

        service.sweepTimeouts();

        // SENT 超时 + PENDING 卡死各一次条件更新
        verify(mapper, org.mockito.Mockito.times(2)).update(any(), any());
    }

    private Device device(String status) {
        Device device = new Device();
        device.setDeviceId("dev-1");
        device.setTenantId("default");
        device.setProductId(10L);
        device.setStatus(status);
        return device;
    }
}
