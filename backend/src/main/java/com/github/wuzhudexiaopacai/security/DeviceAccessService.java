package com.github.wuzhudexiaopacai.security;

import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 按设备校验当前用户的数据范围；不存在返回 404，越界返回 403。 */
@Service
@RequiredArgsConstructor
public class DeviceAccessService {

    private final DeviceLookupService deviceLookupService;
    private final DataScopeService dataScopeService;

    public Device require(String deviceId) {
        Device device = deviceLookupService.getByDeviceId(deviceId);
        if (device == null) {
            throw new BizException(404, "设备不存在");
        }
        return require(device);
    }

    public Device require(Device device) {
        DataScope scope = dataScopeService.current();
        if (!scope.allows(device.getSpaceId())) {
            // 不区分「不存在」与「无权限」，避免通过错误码探测设备
            throw new BizException(403, "无权访问该设备");
        }
        return device;
    }
}
