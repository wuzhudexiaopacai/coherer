package com.github.wuzhudexiaopacai.ota.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.ota.entity.Firmware;
import com.github.wuzhudexiaopacai.ota.entity.OtaTask;
import com.github.wuzhudexiaopacai.ota.event.OtaTaskFinishedEvent;
import com.github.wuzhudexiaopacai.ota.mapper.FirmwareMapper;
import com.github.wuzhudexiaopacai.ota.mapper.OtaTaskMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class OtaProgressServiceTest {

    private OtaTaskMapper taskMapper;
    private FirmwareMapper firmwareMapper;
    private DeviceMapper deviceMapper;
    private ApplicationEventPublisher eventPublisher;
    private OtaProgressService service;
    private OtaTask task;

    @BeforeEach
    void setUp() {
        for (Class<?> type : List.of(OtaTask.class, Firmware.class, Device.class)) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
        }
        taskMapper = mock(OtaTaskMapper.class);
        firmwareMapper = mock(FirmwareMapper.class);
        deviceMapper = mock(DeviceMapper.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new OtaProgressService(taskMapper, firmwareMapper, deviceMapper,
                mock(DeviceLookupService.class), new ObjectMapper(),
                eventPublisher, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "retryBackoffSeconds", 30L);
        task = new OtaTask();
        task.setId(1L);
        task.setFirmwareId(10L);
        task.setDeviceId("dev-1");
        task.setStatus("SENT");
        task.setProgress(0);
        task.setAttempt(1);
        task.setMaxAttempts(1);
        task.setRolloutId(50L);
        Firmware firmware = new Firmware();
        firmware.setId(10L);
        firmware.setVersion("1.2.0");
        when(firmwareMapper.selectById(10L)).thenReturn(firmware);
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        when(deviceMapper.update(any(), any())).thenReturn(1);
    }

    @Test
    void progress_downloading_updatesPercent() {
        service.onProgress("dev-1", payload("{\"taskId\":1,\"status\":\"DOWNLOADING\",\"progress\":45}"));

        verify(taskMapper).update(org.mockito.ArgumentMatchers.isNull(), any());
        assertEquals("DOWNLOADING", task.getStatus());
        assertEquals(45, task.getProgress());
    }

    @Test
    void progress_success_finishesAt100() {
        service.onProgress("dev-1", payload("{\"taskId\":1,\"status\":\"SUCCESS\",\"progress\":98}"));

        assertEquals("SUCCESS", task.getStatus());
        assertEquals(100, task.getProgress());
        assertNotNull(task.getFinishedAt());
        verify(eventPublisher).publishEvent(any(OtaTaskFinishedEvent.class));
        verify(deviceMapper).update(any(), any());
    }

    @Test
    void progress_failed_terminalRecordsTruncatedError() {
        String longError = "x".repeat(400);
        service.onProgress("dev-1",
                payload("{\"taskId\":1,\"status\":\"FAILED\",\"attempt\":1,\"error\":\""
                        + longError + "\"}"));

        assertEquals("FAILED", task.getStatus());
        assertEquals(255, task.getError().length());
        verify(eventPublisher).publishEvent(any(OtaTaskFinishedEvent.class));
    }

    @Test
    void progress_failed_withAttemptsLeft_schedulesRetry() {
        task.setMaxAttempts(3);

        service.onProgress("dev-1",
                payload("{\"taskId\":1,\"status\":\"FAILED\",\"attempt\":1,\"error\":\"boom\"}"));

        assertEquals("PENDING", task.getStatus());
        assertEquals(2, task.getAttempt());
        assertNotNull(task.getNextAttemptAt());
        assertNull(task.getFinishedAt());
        verify(eventPublisher, never()).publishEvent(any(OtaTaskFinishedEvent.class));
    }

    @Test
    void progress_staleAttempt_isIgnored() {
        task.setAttempt(2);
        task.setStatus("SENT");

        service.onProgress("dev-1",
                payload("{\"taskId\":1,\"status\":\"FAILED\",\"attempt\":1,\"error\":\"old\"}"));

        assertEquals(2, task.getAttempt());
        assertEquals("SENT", task.getStatus());
        verify(taskMapper, never()).update(any(), any());
    }

    @Test
    void progress_progress100_marksSuccess() {
        service.onProgress("dev-1", payload("{\"taskId\":1,\"status\":\"DOWNLOADING\",\"progress\":100}"));

        assertEquals("SUCCESS", task.getStatus());
    }

    @Test
    void progress_wrongDevice_isIgnored() {
        service.onProgress("dev-2", payload("{\"taskId\":1,\"status\":\"SUCCESS\"}"));

        assertEquals("SENT", task.getStatus());
        verify(taskMapper, never()).update(any(), any());
    }

    @Test
    void progress_terminalTask_isIgnored() {
        task.setStatus("SUCCESS");

        service.onProgress("dev-1", payload("{\"taskId\":1,\"status\":\"FAILED\"}"));

        assertEquals("SUCCESS", task.getStatus());
        verify(taskMapper, never()).update(any(), any());
    }

    @Test
    void progress_malformedPayload_doesNotThrow() {
        service.onProgress("dev-1", "not-json".getBytes(StandardCharsets.UTF_8));

        assertEquals("SENT", task.getStatus());
        assertNull(task.getError());
        assertTrue(task.getAttempt() == 1);
    }

    private byte[] payload(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
