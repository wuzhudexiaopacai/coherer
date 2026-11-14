package com.github.wuzhudexiaopacai.telemetry.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wuzhudexiaopacai.alarm.service.AlarmRecordService;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.telemetry.TelemetryBatchWriter;
import com.github.wuzhudexiaopacai.telemetry.TelemetryIngestBuffer;
import com.github.wuzhudexiaopacai.telemetry.TelemetryTdStore;
import com.github.wuzhudexiaopacai.thingmodel.service.ThingModelService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TelemetryServiceTest {

    private TelemetryIngestBuffer buffer;
    private AlarmRecordService alarmService;
    private SimpleMeterRegistry registry;
    private TelemetryService service;

    @BeforeEach
    void setUp() {
        Device device = new Device();
        device.setDeviceId("dev-1");
        device.setTenantId("default");
        device.setProductId(10L);
        device.setSpaceId(1L);

        DeviceLookupService deviceLookup = mock(DeviceLookupService.class);
        when(deviceLookup.getByDeviceId("dev-1")).thenReturn(device);
        ThingModelService thingModel = mock(ThingModelService.class);
        when(thingModel.propertiesCached(10L)).thenReturn(List.of());
        buffer = mock(TelemetryIngestBuffer.class);
        when(buffer.offer(any(), anyString())).thenReturn(true);
        TelemetryIdempotencyService idempotency = mock(TelemetryIdempotencyService.class);
        when(idempotency.key(any(), any(), any(Long.class), any())).thenReturn("k");
        when(idempotency.markIfNew("k")).thenReturn(true);
        alarmService = mock(AlarmRecordService.class);
        registry = new SimpleMeterRegistry();

        service = new TelemetryService(new ObjectMapper(), deviceLookup, thingModel,
                mock(TelemetryTdStore.class), mock(TelemetryBatchWriter.class), buffer,
                idempotency, alarmService, registry);
    }

    @Test
    void oversizedPayload_isRejectedBeforeBuffer() {
        String payload = "{\"id\":1,\"ts\":1,\"params\":{\"blob\":\""
                + "x".repeat(3000) + "\"}}";

        service.ingest("default", "dev-1", payload.getBytes(StandardCharsets.UTF_8));

        verify(buffer, never()).offer(any(), anyString());
        verify(alarmService, never()).evaluate(any(), any());
        org.junit.jupiter.api.Assertions.assertEquals(1.0,
                registry.counter("coherer.telemetry.ingested", "result", "rejected").count());
    }

    @Test
    void validPayload_enqueuesAndEvaluatesAlarm() {
        String payload = "{\"id\":2,\"ts\":1,\"params\":{\"temperature\":20}}";

        service.ingest("default", "dev-1", payload.getBytes(StandardCharsets.UTF_8));

        verify(buffer).offer(any(), eq("k"));
        verify(alarmService).evaluate(any(), eq(Map.of("temperature", 20)));
        org.junit.jupiter.api.Assertions.assertEquals(1.0,
                registry.counter("coherer.telemetry.ingested", "result", "ok").count());
    }
}
