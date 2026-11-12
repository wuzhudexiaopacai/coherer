package com.github.wuzhudexiaopacai.device.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.common.Ids;
import com.github.wuzhudexiaopacai.common.PageResult;
import com.github.wuzhudexiaopacai.device.dto.DeviceRegisterRequest;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.device.vo.DeviceRegisterResult;
import com.github.wuzhudexiaopacai.device.vo.DeviceVO;
import com.github.wuzhudexiaopacai.product.entity.Product;
import com.github.wuzhudexiaopacai.product.mapper.ProductMapper;
import com.github.wuzhudexiaopacai.security.SecretCipher;
import com.github.wuzhudexiaopacai.shadow.service.ShadowService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DeviceService extends ServiceImpl<DeviceMapper, Device> {

    private final ProductMapper productMapper;
    private final SecretCipher secretCipher;
    private final ShadowService shadowService;

    @Transactional(rollbackFor = Exception.class)
    public List<DeviceRegisterResult> register(DeviceRegisterRequest request) {
        Product product = productMapper.selectById(request.getProductId());
        if (product == null) {
            throw new BizException(404, "产品不存在");
        }
        if (!"ACTIVE".equals(product.getStatus())) {
            throw new BizException("产品已停用，不能注册设备");
        }

        int count = request.getCount() == null ? 1 : request.getCount();
        String prefix = StringUtils.hasText(request.getNamePrefix())
                ? request.getNamePrefix()
                : product.getProductKey();

        List<Device> devices = new ArrayList<>(count);
        List<DeviceRegisterResult> results = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            String deviceId = Ids.deviceId();
            String secret = Ids.deviceSecret();
            String name = prefix + "-" + i;

            Device device = new Device();
            device.setTenantId(product.getTenantId());
            device.setProductId(product.getId());
            device.setDeviceId(deviceId);
            device.setName(name);
            device.setSecretCipher(secretCipher.encrypt(secret));
            device.setStatus("INACTIVE");

            devices.add(device);
            results.add(new DeviceRegisterResult(deviceId, name, secret));
        }
        saveBatch(devices);
        shadowService.createForDevices(devices);
        return results;
    }

    public PageResult<DeviceVO> page(long current, long size, Long productId,
                                     String status, String keyword) {
        Page<Device> page = page(new Page<>(current, size),
                Wrappers.<Device>lambdaQuery()
                        .eq(productId != null, Device::getProductId, productId)
                        .eq(StringUtils.hasText(status), Device::getStatus, status)
                        .and(StringUtils.hasText(keyword),
                                w -> w.like(Device::getDeviceId, keyword)
                                        .or()
                                        .like(Device::getName, keyword))
                        .orderByDesc(Device::getCreatedAt));
        return PageResult.of(page.convert(this::toVO));
    }

    public DeviceVO get(Long id) {
        Device device = getById(id);
        if (device == null) {
            throw new BizException(404, "设备不存在");
        }
        return toVO(device);
    }

    private DeviceVO toVO(Device device) {
        DeviceVO vo = new DeviceVO();
        BeanUtils.copyProperties(device, vo);
        return vo;
    }
}
