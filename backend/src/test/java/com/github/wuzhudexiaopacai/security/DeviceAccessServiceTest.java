package com.github.wuzhudexiaopacai.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class DeviceAccessServiceTest {

    private DeviceLookupService deviceLookupService;
    private DataScopeService dataScopeService;
    private DeviceAccessService service;

    @BeforeEach
    void setUp() {
        deviceLookupService = mock(DeviceLookupService.class);
        dataScopeService = mock(DataScopeService.class);
        service = new DeviceAccessService(deviceLookupService, dataScopeService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("viewer", null,
                        List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deviceOutsideScope_isForbidden() {
        when(deviceLookupService.getByDeviceId("dev-1")).thenReturn(device("dev-1", 20L));
        when(dataScopeService.current()).thenReturn(DataScope.of(10L, List.of(10L)));

        BizException exception = assertThrows(BizException.class,
                () -> service.require("dev-1"));
        assertEquals(403, exception.getCode());
    }

    @Test
    void deviceInsideScope_isReturned() {
        Device device = device("dev-2", 10L);
        when(deviceLookupService.getByDeviceId("dev-2")).thenReturn(device);
        when(dataScopeService.current()).thenReturn(DataScope.of(10L, List.of(10L, 11L)));

        assertEquals("dev-2", service.require("dev-2").getDeviceId());
    }

    @Test
    void unknownDevice_isNotFound() {
        when(deviceLookupService.getByDeviceId("ghost")).thenReturn(null);

        BizException exception = assertThrows(BizException.class,
                () -> service.require("ghost"));
        assertEquals(404, exception.getCode());
    }

    private Device device(String deviceId, Long spaceId) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setSpaceId(spaceId);
        return device;
    }
}
