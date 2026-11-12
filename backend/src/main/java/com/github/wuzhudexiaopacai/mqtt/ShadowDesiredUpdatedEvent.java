package com.github.wuzhudexiaopacai.mqtt;

import java.util.Map;

/** 影子期望值变更事件：由 ShadowService 发布，MqttGateway 转为 MQTT retained 消息下发给设备。 */
public record ShadowDesiredUpdatedEvent(
        String tenantId,
        String deviceId,
        Map<String, Object> desired,
        long version) {
}
