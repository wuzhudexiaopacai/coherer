package com.github.wuzhudexiaopacai.ota.service;

/** OTA 指令下发通道抽象，避免 OtaService 与 MqttGateway 循环依赖。 */
public interface OtaPublisher {

    boolean publishOta(String tenantId, String deviceId, String hostKey,
                       Long taskId, Long firmwareId, String version,
                       String downloadUrl, String sha256, long sizeBytes);
}
