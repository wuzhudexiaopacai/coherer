package com.github.wuzhudexiaopacai.device.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.device.vo.DeviceRegisterResult;
import com.github.wuzhudexiaopacai.device.vo.DeviceVO;
import com.github.wuzhudexiaopacai.product.mapper.ProductMapper;
import com.github.wuzhudexiaopacai.security.DataScope;
import com.github.wuzhudexiaopacai.security.SecretCipher;
import com.github.wuzhudexiaopacai.shadow.service.ShadowService;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DeviceServiceTest {

    private DeviceMapper deviceMapper;
    private SecretCipher secretCipher;
    private ShadowService shadowService;
    private DeviceLookupService deviceLookupService;
    private DeviceService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Device.class);
        deviceMapper = mock(DeviceMapper.class);
        secretCipher = mock(SecretCipher.class);
        shadowService = mock(ShadowService.class);
        deviceLookupService = mock(DeviceLookupService.class);
        service = new DeviceService(mock(ProductMapper.class), secretCipher, shadowService,
                mock(SpaceService.class), deviceLookupService);
        ReflectionTestUtils.setField(service, "baseMapper", deviceMapper);
        when(deviceMapper.updateById(any(Device.class))).thenReturn(1);
        when(deviceMapper.deleteById(any(Device.class))).thenReturn(1);
    }

    @Test
    void disable_setsDisabledAndInvalidatesCache() {
        when(deviceMapper.selectById(10L)).thenReturn(device(10L, "ONLINE", 1L));

        DeviceVO vo = service.setEnabled(10L, DataScope.all(), false);

        assertEquals("DISABLED", vo.getStatus());
        verify(deviceMapper).updateById(any(Device.class));
        verify(deviceLookupService).invalidate("dev-10");
    }

    @Test
    void enable_fromDisabled_setsInactive() {
        when(deviceMapper.selectById(10L)).thenReturn(device(10L, "DISABLED", 1L));

        DeviceVO vo = service.setEnabled(10L, DataScope.all(), true);

        assertEquals("INACTIVE", vo.getStatus());
        verify(deviceLookupService).invalidate("dev-10");
    }

    @Test
    void lifecycle_onForeignDevice_isForbidden() {
        when(deviceMapper.selectById(10L)).thenReturn(device(10L, "ONLINE", 99L));
        DataScope scope = DataScope.of(1L, List.of(1L, 2L));

        BizException exception = assertThrows(BizException.class,
                () -> service.setEnabled(10L, scope, false));

        assertEquals(403, exception.getCode());
    }

    @Test
    void rotateSecret_returnsNewSecretOnceAndInvalidatesCache() {
        Device device = device(10L, "ONLINE", 1L);
        device.setSecretCipher("old-cipher");
        when(deviceMapper.selectById(10L)).thenReturn(device);
        when(secretCipher.encrypt(any())).thenReturn("new-cipher");

        DeviceRegisterResult result = service.rotateSecret(10L, DataScope.all());

        assertEquals("dev-10", result.getDeviceId());
        assertEquals(43, result.getDeviceSecret().length());
        assertEquals("new-cipher", device.getSecretCipher());
        verify(deviceLookupService).invalidate("dev-10");
    }

    @Test
    void delete_removesShadowAndDevice() {
        when(deviceMapper.selectById(10L)).thenReturn(device(10L, "OFFLINE", 1L));

        service.delete(10L, DataScope.all());

        verify(shadowService).removeByDeviceId("dev-10");
        verify(deviceMapper).deleteById(10L);
        verify(deviceLookupService).invalidate("dev-10");
    }

    @Test
    void disable_alreadyDisabled_isIdempotent() {
        when(deviceMapper.selectById(10L)).thenReturn(device(10L, "DISABLED", 1L));

        DeviceVO vo = service.setEnabled(10L, DataScope.all(), false);

        assertEquals("DISABLED", vo.getStatus());
        org.mockito.Mockito.verify(deviceMapper, org.mockito.Mockito.never())
                .updateById(any(Device.class));
    }

    private Device device(Long id, String status, Long spaceId) {
        Device device = new Device();
        device.setId(id);
        device.setDeviceId("dev-" + id);
        device.setName("设备" + id);
        device.setProductId(1L);
        device.setTenantId("default");
        device.setSpaceId(spaceId);
        device.setStatus(status);
        return device;
    }
}
