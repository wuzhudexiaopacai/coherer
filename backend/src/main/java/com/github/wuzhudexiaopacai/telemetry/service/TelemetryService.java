package com.github.wuzhudexiaopacai.telemetry.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wuzhudexiaopacai.alarm.service.AlarmRecordService;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.telemetry.TelemetryTdStore;
import com.github.wuzhudexiaopacai.telemetry.vo.TelemetryVO;
import com.github.wuzhudexiaopacai.thingmodel.entity.ThingModel;
import com.github.wuzhudexiaopacai.thingmodel.service.ThingModelService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 遥测处理：按物模型校验后写入 TDengine。
 * 未定义物模型的产品放行（兼容性）；定义了物模型则严格校验未知属性、类型与范围。
 */
@Service
@RequiredArgsConstructor
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

    private final ObjectMapper objectMapper;
    private final DeviceLookupService deviceLookupService;
    private final ThingModelService thingModelService;
    private final TelemetryTdStore tdStore;
    private final AlarmRecordService alarmRecordService;

    public void ingest(String tenantId, String deviceId, byte[] payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode paramsNode = root.path("params");
            if (!paramsNode.isObject()) {
                log.debug("遥测缺少 params，丢弃 deviceId={}", deviceId);
                return;
            }
            Map<String, Object> params = objectMapper.convertValue(
                    paramsNode, new TypeReference<Map<String, Object>>() {
                    });

            Device device = deviceLookupService.getByDeviceId(deviceId);
            if (device == null) {
                log.debug("收到未注册设备的遥测，丢弃 deviceId={}", deviceId);
                return;
            }

            List<ThingModel> properties = thingModelService.propertiesCached(device.getProductId());
            Map<String, Object> normalized = validate(properties, params);
            long ts = root.path("ts").asLong(System.currentTimeMillis());
            tdStore.insert(tenantId, deviceId, device.getProductId(), ts, normalized);
            alarmRecordService.evaluate(device, normalized);
        } catch (IllegalArgumentException e) {
            log.warn("遥测校验失败 deviceId={} err={}", deviceId, e.getMessage());
        } catch (Exception e) {
            log.warn("遥测处理失败 deviceId={} err={}", deviceId, e.getMessage());
        }
    }

    public List<TelemetryVO> recent(String deviceId, int limit) {
        return tdStore.recent(deviceId, limit);
    }

    private Map<String, Object> validate(List<ThingModel> properties, Map<String, Object> params) {
        if (properties == null || properties.isEmpty()) {
            return params;
        }
        Map<String, ThingModel> definitions = properties.stream()
                .collect(Collectors.toMap(ThingModel::getIdentifier, p -> p, (a, b) -> a));

        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            ThingModel definition = definitions.get(entry.getKey());
            if (definition == null) {
                throw new IllegalArgumentException("未知属性: " + entry.getKey());
            }
            result.put(entry.getKey(), normalize(definition, entry.getValue()));
        }
        return result;
    }

    private Object normalize(ThingModel definition, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("属性 " + definition.getIdentifier() + " 值为空");
        }
        String dataType = definition.getDataType() == null ? "STRING" : definition.getDataType();
        Object normalized = switch (dataType) {
            case "INT" -> {
                double d = requireNumber(definition, value).doubleValue();
                if (d != Math.rint(d)) {
                    throw new IllegalArgumentException(
                            "属性 " + definition.getIdentifier() + " 需要整数");
                }
                long asLong = (long) d;
                // 全局 Long -> String 序列化用于雪花 ID，这里显式保留数值类型
                if (asLong >= Integer.MIN_VALUE && asLong <= Integer.MAX_VALUE) {
                    yield Integer.valueOf((int) asLong);
                }
                yield Long.valueOf(asLong);
            }
            case "FLOAT", "DOUBLE" -> requireNumber(definition, value).doubleValue();
            case "BOOL" -> {
                if (!(value instanceof Boolean)) {
                    throw new IllegalArgumentException(
                            "属性 " + definition.getIdentifier() + " 需要布尔值");
                }
                yield value;
            }
            case "STRING" -> {
                if (!(value instanceof String)) {
                    throw new IllegalArgumentException(
                            "属性 " + definition.getIdentifier() + " 需要字符串");
                }
                yield value;
            }
            default -> throw new IllegalArgumentException(
                    "属性 " + definition.getIdentifier() + " dataType 不支持: " + dataType);
        };

        if (normalized instanceof Number number) {
            checkRange(definition, number.doubleValue());
        }
        return normalized;
    }

    private Number requireNumber(ThingModel definition, Object value) {
        if (value instanceof Number number) {
            return number;
        }
        throw new IllegalArgumentException("属性 " + definition.getIdentifier() + " 需要数值");
    }

    private void checkRange(ThingModel definition, double value) {
        Map<String, Object> spec = definition.getSpec();
        if (spec == null) {
            return;
        }
        if (spec.get("min") instanceof Number min && value < min.doubleValue()) {
            throw new IllegalArgumentException(
                    "属性 " + definition.getIdentifier() + " 小于下限 " + min);
        }
        if (spec.get("max") instanceof Number max && value > max.doubleValue()) {
            throw new IllegalArgumentException(
                    "属性 " + definition.getIdentifier() + " 超过上限 " + max);
        }
    }
}
