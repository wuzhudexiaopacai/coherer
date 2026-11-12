package com.github.wuzhudexiaopacai.telemetry.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.telemetry.entity.Telemetry;
import com.github.wuzhudexiaopacai.telemetry.mapper.TelemetryMapper;
import com.github.wuzhudexiaopacai.telemetry.vo.TelemetryVO;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TelemetryService extends ServiceImpl<TelemetryMapper, Telemetry> {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

    private final DeviceMapper deviceMapper;

    /** 消费一条遥测：校验设备存在后入库（M2 起改用 Redis 缓存设备信息并写入 TDengine）。 */
    public void ingest(String tenantId, String deviceId, long tsMillis, Map<String, Object> params) {
        Device device = deviceMapper.selectOne(Wrappers.<Device>lambdaQuery()
                .eq(Device::getDeviceId, deviceId)
                .last("limit 1"));
        if (device == null) {
            log.debug("收到未注册设备的遥测，丢弃 deviceId={}", deviceId);
            return;
        }
        Telemetry telemetry = new Telemetry();
        telemetry.setTenantId(tenantId);
        telemetry.setProductId(device.getProductId());
        telemetry.setDeviceId(deviceId);
        telemetry.setTs(OffsetDateTime.ofInstant(Instant.ofEpochMilli(tsMillis), ZoneOffset.UTC));
        telemetry.setParams(params);
        save(telemetry);
    }

    public List<TelemetryVO> recent(String deviceId, int limit) {
        return list(Wrappers.<Telemetry>lambdaQuery()
                .eq(Telemetry::getDeviceId, deviceId)
                .orderByDesc(Telemetry::getTs)
                .last("limit " + limit))
                .stream()
                .map(this::toVO)
                .toList();
    }

    private TelemetryVO toVO(Telemetry telemetry) {
        TelemetryVO vo = new TelemetryVO();
        vo.setTs(telemetry.getTs());
        vo.setParams(telemetry.getParams());
        vo.setReceivedAt(telemetry.getReceivedAt());
        return vo;
    }
}
