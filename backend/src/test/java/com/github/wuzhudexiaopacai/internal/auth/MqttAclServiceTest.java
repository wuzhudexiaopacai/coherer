package com.github.wuzhudexiaopacai.internal.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.wuzhudexiaopacai.auth.entity.SysUser;
import com.github.wuzhudexiaopacai.auth.mapper.SysUserMapper;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MqttAclServiceTest {

    private MqttAclService service;

    @BeforeEach
    void setUp() {
        DeviceLookupService deviceLookupService = mock(DeviceLookupService.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SpaceService spaceService = mock(SpaceService.class);

        // dev-1 属于空间 10（华东园区），dev-2 属于空间 20
        Device device1 = new Device();
        device1.setDeviceId("dev-1");
        device1.setTenantId("default");
        device1.setSpaceId(10L);
        when(deviceLookupService.getByDeviceId("dev-1")).thenReturn(device1);

        Device device2 = new Device();
        device2.setDeviceId("dev-2");
        device2.setTenantId("default");
        device2.setSpaceId(20L);
        when(deviceLookupService.getByDeviceId("dev-2")).thenReturn(device2);

        // 用户 100：全平台数据范围；用户 101：仅空间 10
        SysUser allSpaceUser = new SysUser();
        allSpaceUser.setId(100L);
        allSpaceUser.setStatus("ACTIVE");
        allSpaceUser.setSpaceId(null);
        when(userMapper.selectById(100L)).thenReturn(allSpaceUser);

        SysUser scopedUser = new SysUser();
        scopedUser.setId(101L);
        scopedUser.setStatus("ACTIVE");
        scopedUser.setSpaceId(10L);
        when(userMapper.selectById(101L)).thenReturn(scopedUser);

        when(spaceService.descendantIds(10L)).thenReturn(List.of(10L));

        service = new MqttAclService(deviceLookupService, userMapper, spaceService);
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
    void authorize_webAccountWithAllSpaceScope_allowsTelemetrySubscribe() {
        assertTrue(service.authorize("web-abc", "web:100:1",
                "default/+/thing/property/report", "subscribe"));
        assertTrue(service.authorize("web-abc", "web:100:1",
                "default/dev-1/thing/property/report", "subscribe"));
    }

    @Test
    void authorize_webAccount_deniesPublishAndOtherTopics() {
        assertFalse(service.authorize("web-abc", "web:100:1",
                "default/dev-1/thing/property/report", "publish"));
        assertFalse(service.authorize("web-abc", "web:100:1",
                "default/+/shadow/update", "subscribe"));
        assertFalse(service.authorize("web-abc", "web:100:1", "#", "subscribe"));
    }

    @Test
    void authorize_webAccountWithSpaceScope_onlyAllowsOwnSpaceDevices() {
        assertTrue(service.authorize("web-abc", "web:101:1",
                "default/dev-1/thing/property/report", "subscribe"));
        assertFalse(service.authorize("web-abc", "web:101:1",
                "default/dev-2/thing/property/report", "subscribe"));
        // 受限用户禁止通配符订阅
        assertFalse(service.authorize("web-abc", "web:101:1",
                "default/+/thing/property/report", "subscribe"));
    }

    @Test
    void authorize_webAccountWithUnknownUser_denies() {
        assertFalse(service.authorize("web-abc", "web:999:1",
                "default/dev-1/thing/property/report", "subscribe"));
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
