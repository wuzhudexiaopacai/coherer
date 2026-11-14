package com.github.wuzhudexiaopacai.command.service;

import java.util.Map;

/** 命令下发通道抽象，避免 CommandService 与 MqttGateway 循环依赖。 */
public interface CommandPublisher {

    /**
     * @return false 表示当前 MQTT 未连接，命令无法下发
     */
    boolean publishCommand(String tenantId, String deviceId, String identifier,
                           String requestId, Map<String, Object> params);
}
