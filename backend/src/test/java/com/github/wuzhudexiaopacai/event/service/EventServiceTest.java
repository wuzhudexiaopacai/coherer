package com.github.wuzhudexiaopacai.event.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.event.entity.DeviceEvent;
import com.github.wuzhudexiaopacai.event.mapper.DeviceEventMapper;
import com.github.wuzhudexiaopacai.thingmodel.entity.ThingModel;
import com.github.wuzhudexiaopacai.thingmodel.service.ThingModelService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

class EventServiceTest {

    private DeviceEventMapper mapper;
    private EventService service;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), DeviceEvent.class);
        mapper = mock(DeviceEventMapper.class);
        DeviceLookupService deviceLookup = mock(DeviceLookupService.class);
        Device device = new Device();
        device.setDeviceId("dev-1");
        device.setTenantId("default");
        device.setProductId(10L);
        when(deviceLookup.getByDeviceId("dev-1")).thenReturn(device);
        ThingModelService thingModel = mock(ThingModelService.class);
        when(thingModel.eventsCached(10L)).thenReturn(List.of(eventModel()));
        registry = new SimpleMeterRegistry();
        service = new EventService(new ObjectMapper(), deviceLookup, thingModel, registry);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.insert((DeviceEvent) any())).thenReturn(1);
    }

    @Test
    void validEvent_isPersisted() {
        service.ingest("default", "dev-1", "highTempAlarm",
                payload("{\"id\":\"evt-1\",\"ts\":1000,\"params\":{\"temperature\":36.5}}"));

        ArgumentCaptor<DeviceEvent> captor = ArgumentCaptor.forClass(DeviceEvent.class);
        verify(mapper).insert(captor.capture());
        DeviceEvent event = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("highTempAlarm", event.getIdentifier());
        org.junit.jupiter.api.Assertions.assertEquals("evt-1", event.getEventId());
        org.junit.jupiter.api.Assertions.assertEquals(36.5,
                ((Number) event.getParams().get("temperature")).doubleValue());
    }

    @Test
    void unknownEvent_isRejected() {
        service.ingest("default", "dev-1", "doorOpen",
                payload("{\"id\":\"evt-1\",\"params\":{\"temperature\":36.5}}"));

        verify(mapper, never()).insert((DeviceEvent) any());
        org.junit.jupiter.api.Assertions.assertEquals(1.0,
                registry.counter("coherer.event.ingested", "result", "rejected").count());
    }

    @Test
    void unknownParam_isRejected() {
        service.ingest("default", "dev-1", "highTempAlarm",
                payload("{\"id\":\"evt-2\",\"params\":{\"bogus\":1}}"));

        verify(mapper, never()).insert((DeviceEvent) any());
    }

    @Test
    void duplicateEventId_isDropped() {
        when(mapper.insert((DeviceEvent) any()))
                .thenThrow(new DuplicateKeyException("duplicate"));

        service.ingest("default", "dev-1", "highTempAlarm",
                payload("{\"id\":\"evt-3\",\"params\":{\"temperature\":36.5}}"));

        org.junit.jupiter.api.Assertions.assertEquals(1.0,
                registry.counter("coherer.event.ingested", "result", "duplicate").count());
    }

    @Test
    void missingParams_isInvalid() {
        service.ingest("default", "dev-1", "highTempAlarm", payload("{\"id\":\"evt-4\"}"));

        verify(mapper, never()).insert((DeviceEvent) any());
    }

    private byte[] payload(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private ThingModel eventModel() {
        ThingModel model = new ThingModel();
        model.setType("EVENT");
        model.setIdentifier("highTempAlarm");
        model.setName("高温告警");
        model.setSpec(Map.of("params", Map.of("temperature", "FLOAT")));
        return model;
    }
}
