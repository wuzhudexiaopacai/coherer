package com.github.wuzhudexiaopacai.internal.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MqttAclServiceTest {

    private MqttAclService service;

    @BeforeEach
    void setUp() {
        DeviceLookupService deviceLookupService = mock(DeviceLookupService.class);
        Device device = new Device();
        device.setDeviceId("dev-1");
        device.setTenantId("default");
        when(deviceLookupService.getByDeviceId("dev-1")).thenReturn(device);

        service = new MqttAclService(deviceLookupService);
        ReflectionTestUtils.setField(service, "ingestClientId", "coherer-ingest");
        ReflectionTestUtils.setField(service, "ingestUsername", "service");
    }

    @Test
    void authorize_serviceAccount_allowsAnyTopic() {
        assertTrue(service.authorize("coherer-ingest", "service",
                "default/dev-1/thing/property/report", "publish"));
        assertTrue(service.authorize("coherer-ingest", "service",
                "$share/coherer/+/+/thing/property/report", "subscribe"));
    }

    @Test
    void authorize_webAccount_allowsTelemetrySubscribe() {
        assertTrue(service.authorize("web-abc", "web:1",
                "default/+/thing/property/report", "subscribe"));
        assertTrue(service.authorize("web-abc", "web:1",
                "default/dev-1/thing/property/report", "subscribe"));
        assertTrue(service.authorize("web-abc", "web:1",
                "default/+/status", "subscribe"));
    }

    @Test
    void authorize_webAccount_deniesPublishAndOtherTopics() {
        assertFalse(service.authorize("web-abc", "web:1",
                "default/dev-1/thing/property/report", "publish"));
        assertFalse(service.authorize("web-abc", "web:1",
                "default/+/shadow/update", "subscribe"));
        assertFalse(service.authorize("web-abc", "web:1", "#", "subscribe"));
    }

    @Test
    void authorize_device_allowsOwnSubtree() {
        assertTrue(service.authorize("dev-1", "123",
                "default/dev-1/thing/property/report", "publish"));
        assertTrue(service.authorize("dev-1", "123",
                "default/dev-1/shadow/update", "subscribe"));
        assertTrue(service.authorize("dev-1", "123",
                "default/dev-1/status", "publish"));
    }

    @Test
    void authorize_device_deniesOtherDevicesAndUnknownClients() {
        assertFalse(service.authorize("dev-1", "123",
                "default/dev-2/thing/property/report", "publish"));
        assertFalse(service.authorize("dev-1", "123", "#", "subscribe"));
        assertFalse(service.authorize("unknown-device", "123",
                "default/unknown-device/thing/property/report", "publish"));
    }
}
