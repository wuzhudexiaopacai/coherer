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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wuzhudexiaopacai.ota.entity.OtaTask;
import com.github.wuzhudexiaopacai.ota.event.OtaTaskFinishedEvent;
import com.github.wuzhudexiaopacai.ota.mapper.OtaTaskMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class OtaProgressServiceTest {

    private OtaTaskMapper taskMapper;
    private ApplicationEventPublisher eventPublisher;
    private OtaProgressService service;
    private OtaTask task;

    @BeforeEach
    void setUp() {
        taskMapper = mock(OtaTaskMapper.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new OtaProgressService(taskMapper, new ObjectMapper(),
                eventPublisher, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "retryBackoffSeconds", 30L);
        task = new OtaTask();
        task.setId(1L);
        task.setDeviceId("dev-1");
        task.setStatus("SENT");
        task.setProgress(0);
        task.setAttempt(1);
        task.setMaxAttempts(1);
        task.setRolloutId(50L);
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
    }

    @Test
    void progress_downloading_updatesPercent() {
        service.onProgress("dev-1", payload("{\"taskId\":1,\"status\":\"DOWNLOADING\",\"progress\":45}"));

        assertEquals("DOWNLOADING", task.getStatus());
        assertEquals(45, task.getProgress());
        verify(taskMapper).update(any(), any());
    }

    @Test
    void progress_success_finishesAt100() {
        service.onProgress("dev-1", payload("{\"taskId\":1,\"status\":\"SUCCESS\",\"progress\":98}"));

        assertEquals("SUCCESS", task.getStatus());
        assertEquals(100, task.getProgress());
        assertNotNull(task.getFinishedAt());
        verify(eventPublisher).publishEvent(any(OtaTaskFinishedEvent.class));
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
