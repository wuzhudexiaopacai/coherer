package com.github.wuzhudexiaopacai.host.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.host.dto.HostRegisterRequest;
import com.github.wuzhudexiaopacai.host.entity.IotHost;
import com.github.wuzhudexiaopacai.host.mapper.IotHostMapper;
import com.github.wuzhudexiaopacai.host.vo.HostRegisterResult;
import com.github.wuzhudexiaopacai.security.DataScope;
import com.github.wuzhudexiaopacai.security.SecretCipher;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.github.wuzhudexiaopacai.security.TenantContext;
import com.github.wuzhudexiaopacai.tenant.service.TenantLookupService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.util.ReflectionTestUtils;

class HostServiceTest {

    private IotHostMapper hostMapper;
    private DeviceMapper deviceMapper;
    private SecretCipher secretCipher;
    private SpaceService spaceService;
    private DeviceLookupService deviceLookupService;
    private HostService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotHost.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Device.class);
        hostMapper = mock(IotHostMapper.class);
        deviceMapper = mock(DeviceMapper.class);
        secretCipher = mock(SecretCipher.class);
        spaceService = mock(SpaceService.class);
        deviceLookupService = mock(DeviceLookupService.class);
        TenantLookupService tenantLookup = mock(TenantLookupService.class);
        when(tenantLookup.defaultSpaceId("default")).thenReturn(1L);
        TenantContext.set("default");
        service = new HostService(secretCipher, spaceService, deviceMapper,
                deviceLookupService, mock(HostLookupService.class), tenantLookup);
        ReflectionTestUtils.setField(service, "baseMapper", hostMapper);
        when(hostMapper.insert((IotHost) any())).thenReturn(1);
        when(deviceMapper.updateById((Device) any())).thenReturn(1);
        when(secretCipher.encrypt(any())).thenReturn("cipher");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void register_generatesHostIdentity() {
        when(spaceService.exists(1L)).thenReturn(true);

        HostRegisterRequest request = new HostRegisterRequest();
        request.setName("车间网关");
        HostRegisterResult result = service.register(request, DataScope.all());

        assertNotNull(result.getHostId());
        org.junit.jupiter.api.Assertions.assertTrue(result.getHostId().startsWith("host_"));
        assertEquals(43, result.getHostSecret().length());
        verify(hostMapper).insert(any(IotHost.class));
    }

    @Test
    void register_restrictedUserForeignSpace_isForbidden() {
        HostRegisterRequest request = new HostRegisterRequest();
        request.setName("网关");
        request.setSpaceId(20L);

        BizException exception = assertThrows(BizException.class,
                () -> service.register(request, DataScope.of(10L, List.of(10L))));
        assertEquals(403, exception.getCode());
    }

    @Test
    void bind_setsDeviceHost() {
        when(hostMapper.selectById(1L)).thenReturn(host(1L, 10L));
        Device device = device("dev-1", 10L, null);
        when(deviceLookupService.getByDeviceId("dev-1")).thenReturn(device);
        when(spaceService.descendantIds(10L)).thenReturn(List.of(10L, 11L));

        service.bindDevice(1L, "dev-1", DataScope.all());

        assertEquals(1L, device.getHostId());
        verify(deviceMapper).updateById(device);
    }

    @Test
    void bind_deviceOutsideHostSpace_isRejected() {
        when(hostMapper.selectById(1L)).thenReturn(host(1L, 10L));
        when(deviceLookupService.getByDeviceId("dev-2")).thenReturn(device("dev-2", 20L, null));
        when(spaceService.descendantIds(10L)).thenReturn(List.of(10L));

        BizException exception = assertThrows(BizException.class,
                () -> service.bindDevice(1L, "dev-2", DataScope.all()));
        assertEquals(409, exception.getCode());
    }

    @Test
    void bind_deviceAlreadyBoundToAnotherHost_isRejected() {
        when(hostMapper.selectById(1L)).thenReturn(host(1L, 10L));
        when(deviceLookupService.getByDeviceId("dev-1")).thenReturn(device("dev-1", 10L, 2L));
        when(spaceService.descendantIds(10L)).thenReturn(List.of(10L));

        BizException exception = assertThrows(BizException.class,
                () -> service.bindDevice(1L, "dev-1", DataScope.all()));
        assertEquals(409, exception.getCode());
    }

    @Test
    void delete_unbindsChildrenBeforeRemovingHost() {
        when(hostMapper.selectById(1L)).thenReturn(host(1L, 10L));
        Device child = device("dev-1", 10L, 1L);
        when(deviceMapper.selectList(any())).thenReturn(List.of(child));
        when(hostMapper.deleteById(1L)).thenReturn(1);

        service.delete(1L, DataScope.all());

        verify(deviceMapper).update(any(), any());
        verify(hostMapper).deleteById(1L);
    }

    private IotHost host(Long id, Long spaceId) {
        IotHost host = new IotHost();
        host.setId(id);
        host.setHostId("host_" + id);
        host.setName("主机" + id);
        host.setTenantId("default");
        host.setSpaceId(spaceId);
        host.setStatus("INACTIVE");
        return host;
    }

    private Device device(String deviceId, Long spaceId, Long hostId) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setTenantId("default");
        device.setSpaceId(spaceId);
        device.setHostId(hostId);
        return device;
    }
}
