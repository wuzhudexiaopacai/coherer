package com.github.wuzhudexiaopacai.shadow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.mqtt.ShadowDesiredUpdatedEvent;
import com.github.wuzhudexiaopacai.shadow.dto.ShadowDesiredRequest;
import com.github.wuzhudexiaopacai.shadow.entity.DeviceShadow;
import com.github.wuzhudexiaopacai.shadow.mapper.DeviceShadowMapper;
import com.github.wuzhudexiaopacai.shadow.vo.ShadowVO;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ShadowService extends ServiceImpl<DeviceShadowMapper, DeviceShadow> {

    private static final Logger log = LoggerFactory.getLogger(ShadowService.class);

    private final DeviceLookupService deviceLookupService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /** 设备注册时创建初始影子。 */
    @Transactional(rollbackFor = Exception.class)
    public void createForDevices(List<Device> devices) {
        List<DeviceShadow> shadows = devices.stream().map(device -> {
            DeviceShadow shadow = new DeviceShadow();
            shadow.setTenantId(device.getTenantId());
            shadow.setDeviceId(device.getDeviceId());
            shadow.setDesired(Map.of());
            shadow.setReported(Map.of());
            shadow.setVersion(0L);
            return shadow;
        }).toList();
        saveBatch(shadows);
    }

    public ShadowVO get(String deviceId) {
        return toVO(requireShadow(deviceId));
    }

    /** 设备删除时同步清理影子，避免残留脏数据。 */
    @Transactional(rollbackFor = Exception.class)
    public void removeByDeviceId(String deviceId) {
        remove(Wrappers.<DeviceShadow>lambdaQuery().eq(DeviceShadow::getDeviceId, deviceId));
    }

    /** 设置期望值：合并 desired → 版本 +1 → 发布 MQTT 通知设备（retained，设备上线即收到）。 */
    @Transactional(rollbackFor = Exception.class)
    public ShadowVO updateDesired(String deviceId, ShadowDesiredRequest request) {
        Device device = deviceLookupService.getByDeviceId(deviceId);
        if (device == null) {
            throw new BizException(404, "设备不存在");
        }
        DeviceShadow shadow = requireShadow(deviceId);
        if (request.getVersion() != null && !request.getVersion().equals(shadow.getVersion())) {
            throw new BizException(409,
                    "影子版本冲突（当前版本 " + shadow.getVersion() + "），请刷新后重试");
        }

        Map<String, Object> desired = new LinkedHashMap<>(shadow.getDesired());
        desired.putAll(request.getParams());
        shadow.setDesired(desired);

        if (!updateById(shadow)) {
            throw new BizException(409, "影子已被并发修改，请重试");
        }
        eventPublisher.publishEvent(new ShadowDesiredUpdatedEvent(
                device.getTenantId(), deviceId, desired, shadow.getVersion()));
        return toVO(shadow);
    }

    /** 设备上报实际状态：合并 reported 并递增版本（冲突重试一次）。 */
    public void onReported(String tenantId, String deviceId, byte[] payload) {
        Map<String, Object> reported;
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode reportedNode = root.path("reported");
            if (!reportedNode.isObject()) {
                return;
            }
            reported = objectMapper.convertValue(reportedNode, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("设备影子上报解析失败 deviceId={} err={}", deviceId, e.getMessage());
            return;
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            DeviceShadow shadow = getOne(Wrappers.<DeviceShadow>lambdaQuery()
                    .eq(DeviceShadow::getDeviceId, deviceId));
            if (shadow == null) {
                log.debug("设备影子不存在，忽略上报 deviceId={}", deviceId);
                return;
            }
            Map<String, Object> merged = new LinkedHashMap<>(shadow.getReported());
            merged.putAll(reported);
            shadow.setReported(merged);
            if (updateById(shadow)) {
                return;
            }
        }
        log.warn("设备影子上报更新冲突，放弃本次 deviceId={}", deviceId);
    }

    private DeviceShadow requireShadow(String deviceId) {
        DeviceShadow shadow = getOne(Wrappers.<DeviceShadow>lambdaQuery()
                .eq(DeviceShadow::getDeviceId, deviceId));
        if (shadow == null) {
            throw new BizException(404, "设备影子不存在");
        }
        return shadow;
    }

    private ShadowVO toVO(DeviceShadow shadow) {
        ShadowVO vo = new ShadowVO();
        vo.setDeviceId(shadow.getDeviceId());
        vo.setDesired(shadow.getDesired());
        vo.setReported(shadow.getReported());
        vo.setVersion(shadow.getVersion());
        vo.setUpdatedAt(shadow.getUpdatedAt());
        return vo;
    }
}
