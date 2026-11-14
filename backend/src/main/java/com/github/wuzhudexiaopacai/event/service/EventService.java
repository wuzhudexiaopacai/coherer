package com.github.wuzhudexiaopacai.event.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.event.entity.DeviceEvent;
import com.github.wuzhudexiaopacai.event.mapper.DeviceEventMapper;
import com.github.wuzhudexiaopacai.event.vo.DeviceEventVO;
import com.github.wuzhudexiaopacai.thingmodel.entity.ThingModel;
import com.github.wuzhudexiaopacai.thingmodel.service.ThingModelService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 设备事件上报：按物模型 EVENT 校验后落 PostgreSQL。
 *
 * <p>事件与属性不同，通常是低频、带语义的业务事件（高温、开门、故障），
 * 因此存业务库并提供分页查询；eventId 用于幂等。</p>
 */
@Service
@RequiredArgsConstructor
public class EventService extends ServiceImpl<DeviceEventMapper, DeviceEvent> {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final ObjectMapper objectMapper;
    private final DeviceLookupService deviceLookupService;
    private final ThingModelService thingModelService;
    private final MeterRegistry meterRegistry;

    public void ingest(String tenantId, String deviceId, String identifier, byte[] payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode paramsNode = root.path("params");
            if (!paramsNode.isObject()) {
                meterRegistry.counter("coherer.event.ingested", "result", "invalid").increment();
                log.debug("事件缺少 params，丢弃 deviceId={}", deviceId);
                return;
            }
            Map<String, Object> params = objectMapper.convertValue(
                    paramsNode, new TypeReference<Map<String, Object>>() {
                    });
            Device device = deviceLookupService.getByDeviceId(deviceId);
            if (device == null) {
                meterRegistry.counter("coherer.event.ingested", "result", "unknown_device")
                        .increment();
                return;
            }
            validateEvent(device, identifier, params);

            String eventId = root.path("id").asText("");
            long ts = root.path("ts").asLong(System.currentTimeMillis());
            DeviceEvent event = new DeviceEvent();
            event.setTenantId(tenantId);
            event.setDeviceId(deviceId);
            event.setProductId(device.getProductId());
            event.setIdentifier(identifier);
            event.setEventId(eventId.isBlank() ? null : eventId);
            event.setTs(ts);
            event.setParams(params);
            event.setCreatedAt(OffsetDateTime.now());
            try {
                save(event);
            } catch (DuplicateKeyException e) {
                meterRegistry.counter("coherer.event.ingested", "result", "duplicate").increment();
                log.debug("重复事件已丢弃 deviceId={} eventId={}", deviceId, eventId);
                return;
            }
            meterRegistry.counter("coherer.event.ingested", "result", "ok").increment();
        } catch (BizException | IllegalArgumentException e) {
            meterRegistry.counter("coherer.event.ingested", "result", "rejected").increment();
            log.warn("事件校验失败 deviceId={} identifier={} err={}",
                    deviceId, identifier, e.getMessage());
        } catch (Exception e) {
            meterRegistry.counter("coherer.event.ingested", "result", "error").increment();
            log.warn("事件处理失败 deviceId={} identifier={} err={}",
                    deviceId, identifier, e.getMessage());
        }
    }

    public List<DeviceEventVO> recent(String deviceId, int limit) {
        int size = Math.min(Math.max(limit, 1), 100);
        return list(Wrappers.<DeviceEvent>lambdaQuery()
                .eq(DeviceEvent::getDeviceId, deviceId)
                .orderByDesc(DeviceEvent::getTs)
                .last("limit " + size))
                .stream()
                .map(this::toVO)
                .toList();
    }

    private void validateEvent(Device device, String identifier, Map<String, Object> params) {
        List<ThingModel> events = thingModelService.eventsCached(device.getProductId());
        if (events.isEmpty()) {
            log.debug("产品未定义事件，跳过校验 productId={}", device.getProductId());
            return;
        }
        ThingModel definition = events.stream()
                .filter(event -> event.getIdentifier().equals(identifier))
                .findFirst()
                .orElseThrow(() -> new BizException(400,
                        "事件 " + identifier + " 未在物模型中定义"));
        Map<String, Object> spec = definition.getSpec();
        Object specParams = spec == null ? null : spec.get("params");
        if (!(specParams instanceof Map<?, ?> definitions)) {
            return;
        }
        for (String key : params.keySet()) {
            if (!definitions.containsKey(key)) {
                throw new BizException(400, "事件参数 " + key + " 未在物模型中定义");
            }
        }
        for (Map.Entry<?, ?> entry : definitions.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!params.containsKey(key)) {
                continue;
            }
            validateParamType(key, String.valueOf(entry.getValue()), params.get(key));
        }
    }

    private void validateParamType(String key, String type, Object value) {
        if (value == null) {
            throw new BizException(400, "事件参数 " + key + " 不能为空");
        }
        switch (type.toUpperCase()) {
            case "INT" -> {
                if (!(value instanceof Number number)
                        || number.doubleValue() != Math.rint(number.doubleValue())) {
                    throw new BizException(400, "事件参数 " + key + " 需要整数");
                }
            }
            case "FLOAT", "DOUBLE" -> {
                if (!(value instanceof Number)) {
                    throw new BizException(400, "事件参数 " + key + " 需要数值");
                }
            }
            case "BOOL" -> {
                if (!(value instanceof Boolean)) {
                    throw new BizException(400, "事件参数 " + key + " 需要布尔值");
                }
            }
            case "STRING" -> {
                if (!(value instanceof String)) {
                    throw new BizException(400, "事件参数 " + key + " 需要字符串");
                }
            }
            default -> {
            }
        }
    }

    private DeviceEventVO toVO(DeviceEvent event) {
        DeviceEventVO vo = new DeviceEventVO();
        BeanUtils.copyProperties(event, vo);
        return vo;
    }
}
