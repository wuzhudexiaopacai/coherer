package com.github.wuzhudexiaopacai.mqtt;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wuzhudexiaopacai.command.service.CommandReplyService;
import com.github.wuzhudexiaopacai.device.status.DeviceStatusService;
import com.github.wuzhudexiaopacai.event.service.EventService;
import com.github.wuzhudexiaopacai.host.service.HostDeviceAccessService;
import com.github.wuzhudexiaopacai.host.service.HostStatusService;
import com.github.wuzhudexiaopacai.ota.service.OtaProgressService;
import com.github.wuzhudexiaopacai.shadow.service.ShadowService;
import com.github.wuzhudexiaopacai.telemetry.service.TelemetryService;
import com.github.wuzhudexiaopacai.tenant.service.TenantLookupService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** MQTT 上行入口的租户守卫：租户禁用后既有连接也不能继续写数据。 */
class MqttGatewayDispatchTest {

    private TelemetryService telemetryService;
    private TenantLookupService tenantLookupService;
    private MqttGateway gateway;

    @BeforeEach
    void setUp() {
        telemetryService = mock(TelemetryService.class);
        tenantLookupService = mock(TenantLookupService.class);
        gateway = new MqttGateway(
                telemetryService,
                mock(ShadowService.class),
                mock(DeviceStatusService.class),
                mock(CommandReplyService.class),
                mock(EventService.class),
                mock(HostStatusService.class),
                mock(HostDeviceAccessService.class),
                mock(OtaProgressService.class),
                tenantLookupService,
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                ObservationRegistry.create());
    }

    @Test
    void activeTenant_dispatchesTelemetry() {
        when(tenantLookupService.isActive("acme")).thenReturn(true);

        gateway.dispatch("acme/dev-1/thing/property/report",
                "{\"params\":{\"temperature\":21.5}}".getBytes(StandardCharsets.UTF_8));

        verify(telemetryService).ingest(eq("acme"), eq("dev-1"), any());
    }

    @Test
    void disabledTenant_dropsMessageBeforeProcessing() {
        when(tenantLookupService.isActive("acme")).thenReturn(false);

        gateway.dispatch("acme/dev-1/thing/property/report",
                "{\"params\":{\"temperature\":21.5}}".getBytes(StandardCharsets.UTF_8));

        verify(telemetryService, never()).ingest(anyString(), anyString(), any());
    }

    @Test
    void unknownTopic_isIgnoredWithoutTenantLookup() {
        gateway.dispatch("acme/dev-1/unknown/topic", new byte[0]);

        verify(tenantLookupService, never()).isActive(anyString());
        verify(telemetryService, never()).ingest(anyString(), anyString(), any());
    }
}
