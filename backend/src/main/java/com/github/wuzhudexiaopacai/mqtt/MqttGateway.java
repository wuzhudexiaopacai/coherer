package com.github.wuzhudexiaopacai.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wuzhudexiaopacai.command.service.CommandPublisher;
import com.github.wuzhudexiaopacai.command.service.CommandReplyService;
import com.github.wuzhudexiaopacai.device.status.DeviceStatusService;
import com.github.wuzhudexiaopacai.event.service.EventService;
import com.github.wuzhudexiaopacai.host.service.HostDeviceAccessService;
import com.github.wuzhudexiaopacai.host.service.HostStatusService;
import com.github.wuzhudexiaopacai.ota.service.OtaProgressService;
import com.github.wuzhudexiaopacai.ota.service.OtaPublisher;
import com.github.wuzhudexiaopacai.security.TenantContext;
import com.github.wuzhudexiaopacai.shadow.service.ShadowService;
import com.github.wuzhudexiaopacai.telemetry.service.TelemetryService;
import com.github.wuzhudexiaopacai.tenant.service.TenantLookupService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 平台 MQTT 网关（Paho）：
 * <ul>
 *   <li>共享订阅：遥测上报、影子上报、设备状态</li>
 *   <li>发布：影子期望值（retained，设备上线即可收到）</li>
 * </ul>
 * 共享订阅 {@code $share/coherer/...} 保证多实例部署时消息只被一个实例消费。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "coherer.mqtt.enabled", matchIfMissing = true)
public class MqttGateway implements CommandPublisher, OtaPublisher {

    private static final Logger log = LoggerFactory.getLogger(MqttGateway.class);

    private static final String TELEMETRY_TOPIC = "$share/coherer/+/+/thing/property/report";
    private static final String SHADOW_REPORT_TOPIC = "$share/coherer/+/+/shadow/report";
    private static final String STATUS_TOPIC = "$share/coherer/+/+/status";
    private static final String COMMAND_REPLY_TOPIC = "$share/coherer/+/+/thing/service/reply";
    private static final String EVENT_TOPIC = "$share/coherer/+/+/thing/event/+/post";
    private static final String HOST_STATUS_TOPIC = "$share/coherer/+/+/host/status";
    private static final String HOST_COMMAND_REPLY_TOPIC =
            "$share/coherer/+/+/sub/+/thing/service/reply";
    private static final String HOST_TELEMETRY_TOPIC =
            "$share/coherer/+/+/sub/+/thing/property/report";
    private static final String HOST_EVENT_TOPIC =
            "$share/coherer/+/+/sub/+/thing/event/+/post";
    private static final String HOST_SHADOW_TOPIC =
            "$share/coherer/+/+/sub/+/shadow/report";
    private static final String HOST_DEVICE_STATUS_TOPIC =
            "$share/coherer/+/+/sub/+/status";
    private static final String OTA_PROGRESS_TOPIC = "$share/coherer/+/+/ota/progress";
    private static final String HOST_OTA_PROGRESS_TOPIC =
            "$share/coherer/+/+/sub/+/ota/progress";

    private final TelemetryService telemetryService;
    private final ShadowService shadowService;
    private final DeviceStatusService deviceStatusService;
    private final CommandReplyService commandReplyService;
    private final EventService eventService;
    private final HostStatusService hostStatusService;
    private final HostDeviceAccessService hostDeviceAccessService;
    private final OtaProgressService otaProgressService;
    private final TenantLookupService tenantLookupService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;

    @Value("${coherer.mqtt.broker-host}")
    private String brokerHost;

    @Value("${coherer.mqtt.broker-port}")
    private int brokerPort;

    @Value("${coherer.mqtt.ingest.client-id}")
    private String clientId;

    @Value("${coherer.mqtt.ingest.username}")
    private String username;

    @Value("${coherer.mqtt.ingest.secret}")
    private String secret;

    @Value("${coherer.mqtt.tls-enabled:false}")
    private boolean tlsEnabled;

    @Value("${coherer.mqtt.ca-file:../deploy/certs/ca.crt}")
    private String caFile;

    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private MqttAsyncClient client;

    @PostConstruct
    void init() {
        try {
            client = new MqttAsyncClient(
                    (tlsEnabled ? "ssl" : "tcp") + "://" + brokerHost + ":" + brokerPort,
                    clientId,
                    new MemoryPersistence());
            meterRegistry.gauge("coherer.mqtt.connected", client,
                    c -> c.isConnected() ? 1.0 : 0.0);
            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    log.info("MQTT 网关已连接 EMQX {}（reconnect={}）", serverURI, reconnect);
                    subscribe();
                }

                @Override
                public void connectionLost(Throwable cause) {
                    log.warn("与 EMQX 连接断开，等待自动重连: {}",
                            cause == null ? "unknown" : cause.getMessage());
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    dispatch(topic, message.getPayload());
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
            connect();
        } catch (MqttException e) {
            log.error("初始化 MQTT 客户端失败", e);
        }
    }

    @PreDestroy
    void shutdown() {
        try {
            if (client != null && client.isConnected()) {
                client.disconnect().waitForCompletion(3_000);
            }
            if (client != null) {
                client.close();
            }
        } catch (MqttException e) {
            log.debug("关闭 MQTT 客户端异常: {}", e.getMessage());
        }
    }

    /** 初始连接失败或断线后兜底重连（Paho 自动重连只在成功连接过一次后生效）。 */
    @Scheduled(fixedDelay = 10_000)
    void ensureConnected() {
        if (client != null && !client.isConnected() && !connecting.get()) {
            connect();
        }
    }

    /** 影子期望值变更 → 发布 retained 消息，设备上线即可同步。 */
    @EventListener
    public void onShadowDesiredUpdated(ShadowDesiredUpdatedEvent event) {
        if (client == null || !client.isConnected()) {
            log.warn("MQTT 未连接，影子消息未下发 deviceId={}", event.deviceId());
            return;
        }
        try {
            String topic = event.tenantId() + "/" + event.deviceId() + "/shadow/update";
            byte[] payload = objectMapper.writeValueAsBytes(
                    Map.of("desired", event.desired(), "version", event.version()));
            MqttMessage message = new MqttMessage(payload);
            message.setQos(1);
            message.setRetained(true);
            client.publish(topic, message);
            log.info("已下发影子期望值 topic={} version={}", topic, event.version());
        } catch (Exception e) {
            log.warn("影子消息下发失败 deviceId={} err={}", event.deviceId(), e.getMessage());
        }
    }

    private void connect() {
        if (client == null || !connecting.compareAndSet(false, true)) {
            return;
        }
        try {
            MqttConnectOptions options = new MqttConnectOptions();
            options.setUserName(username);
            options.setPassword(secret.toCharArray());
            options.setCleanSession(true);
            options.setAutomaticReconnect(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(60);
            if (tlsEnabled) {
                options.setSocketFactory(MqttTlsSupport.socketFactory(caFile));
            }
            client.connect(options).waitForCompletion(10_000);
        } catch (MqttException e) {
            log.warn("接入 EMQX 失败: {}", e.getMessage());
        } finally {
            connecting.set(false);
        }
    }

    private void subscribe() {
        try {
            client.subscribe(
                    new String[]{TELEMETRY_TOPIC, SHADOW_REPORT_TOPIC, STATUS_TOPIC,
                            COMMAND_REPLY_TOPIC, EVENT_TOPIC, HOST_STATUS_TOPIC,
                            HOST_COMMAND_REPLY_TOPIC, HOST_TELEMETRY_TOPIC,
                            HOST_EVENT_TOPIC, HOST_SHADOW_TOPIC, HOST_DEVICE_STATUS_TOPIC,
                            OTA_PROGRESS_TOPIC, HOST_OTA_PROGRESS_TOPIC},
                    new int[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1});
            log.info("已共享订阅：直连遥测/影子/状态/回执/事件/OTA进度"
                    + " + 主机状态/回执/子设备遥测/事件/影子/状态/OTA进度");
        } catch (MqttException e) {
            log.error("共享订阅失败: {}", e.getMessage());
        }
    }

    /** 下发设备服务调用；返回 false 表示当前 MQTT 未连接。 */
    @Override
    public boolean publishCommand(String tenantId, String deviceId, String hostKey,
                                  String identifier, String requestId,
                                  java.util.Map<String, Object> params) {
        if (client == null || !client.isConnected()) {
            log.warn("MQTT 未连接，命令未下发 deviceId={} identifier={}", deviceId, identifier);
            return false;
        }
        try {
            String topic = hostKey == null
                    ? tenantId + "/" + deviceId + "/thing/service/" + identifier
                    : tenantId + "/" + hostKey + "/sub/" + deviceId
                            + "/thing/service/" + identifier;
            byte[] payload = objectMapper.writeValueAsBytes(java.util.Map.of(
                    "id", requestId,
                    "deviceId", deviceId,
                    "identifier", identifier,
                    "params", params == null ? java.util.Map.of() : params,
                    "ts", System.currentTimeMillis()));
            MqttMessage message = new MqttMessage(payload);
            message.setQos(1);
            client.publish(topic, message);
            meterRegistry.counter("coherer.command.sent").increment();
            log.info("命令已下发 deviceId={} identifier={} requestId={}",
                    deviceId, identifier, requestId);
            return true;
        } catch (Exception e) {
            log.warn("命令下发失败 deviceId={} identifier={} err={}",
                    deviceId, identifier, e.getMessage());
            return false;
        }
    }

    /** 下发 OTA 升级指令；设备可能直连，也可能挂在边缘主机下。 */
    @Override
    public boolean publishOta(String tenantId, String deviceId, String hostKey,
                              Long taskId, Long firmwareId, String version,
                              String downloadUrl, String sha256, long sizeBytes,
                              int attempt) {
        if (client == null || !client.isConnected()) {
            log.warn("MQTT 未连接，OTA 指令未下发 deviceId={} taskId={}", deviceId, taskId);
            return false;
        }
        try {
            String topic = hostKey == null
                    ? tenantId + "/" + deviceId + "/ota/upgrade"
                    : tenantId + "/" + hostKey + "/sub/" + deviceId + "/ota/upgrade";
            byte[] payload = objectMapper.writeValueAsBytes(java.util.Map.of(
                    "taskId", taskId,
                    "firmwareId", firmwareId,
                    "deviceId", deviceId,
                    "version", version,
                    "url", downloadUrl,
                    "sha256", sha256,
                    "size", sizeBytes,
                    "attempt", attempt,
                    "ts", System.currentTimeMillis()));
            MqttMessage message = new MqttMessage(payload);
            message.setQos(1);
            client.publish(topic, message);
            meterRegistry.counter("coherer.ota.sent").increment();
            log.info("OTA 指令已下发 deviceId={} taskId={} version={} attempt={} viaHost={}",
                    deviceId, taskId, version, attempt, hostKey != null);
            return true;
        } catch (Exception e) {
            log.warn("OTA 指令下发失败 deviceId={} taskId={} err={}",
                    deviceId, taskId, e.getMessage());
            return false;
        }
    }

    /** 包级可见便于单元测试租户守卫；生产路径由 Paho 回调进入。 */
    void dispatch(String topic, byte[] payload) {
        String[] levels = topic.split("/");
        if (levels.length < 3) {
            return;
        }
        String kind = topicKind(levels);
        if (kind == null) {
            return;
        }
        // 已连接设备不会因租户禁用被 Broker 主动断开：上行入口必须再校验租户状态
        if (!tenantLookupService.isActive(levels[0])) {
            log.debug("租户不可用，丢弃 MQTT 上行 topic={}", topic);
            meterRegistry.counter("coherer.mqtt.dropped", "reason", "tenant_inactive").increment();
            return;
        }
        // MQTT 入口不起新 trace 会与上游断链；每条消息独立 span 便于按 traceId 追踪
        Observation.createNotStarted("mqtt.message", observationRegistry)
                .lowCardinalityKeyValue("kind", kind)
                .observe(() -> TenantContext.runAs(levels[0], () -> {
                    if (kind.startsWith("host-") && !"host-status".equals(kind)
                            && !"host-command-reply".equals(kind)) {
                        handleHosted(kind, levels[0], levels[1], levels[3], topic, payload);
                    } else {
                        handle(kind, levels[0], levels[1], topic, payload);
                    }
                }));
    }

    private String topicKind(String[] levels) {
        if (levels.length == 5 && "thing".equals(levels[2])
                && "property".equals(levels[3]) && "report".equals(levels[4])) {
            return "telemetry";
        }
        if (levels.length == 4 && "shadow".equals(levels[2]) && "report".equals(levels[3])) {
            return "shadow";
        }
        if (levels.length == 3 && "status".equals(levels[2])) {
            return "status";
        }
        if (levels.length == 5 && "thing".equals(levels[2])
                && "service".equals(levels[3]) && "reply".equals(levels[4])) {
            return "command-reply";
        }
        if (levels.length == 6 && "thing".equals(levels[2])
                && "event".equals(levels[3]) && "post".equals(levels[5])) {
            return "event";
        }
        if (levels.length == 4 && "host".equals(levels[2])
                && "status".equals(levels[3])) {
            return "host-status";
        }
        if (levels.length == 7 && "sub".equals(levels[2])
                && "thing".equals(levels[4]) && "service".equals(levels[5])
                && "reply".equals(levels[6])) {
            return "host-command-reply";
        }
        if (levels.length == 7 && "sub".equals(levels[2])
                && "thing".equals(levels[4]) && "property".equals(levels[5])
                && "report".equals(levels[6])) {
            return "host-telemetry";
        }
        if (levels.length == 8 && "sub".equals(levels[2])
                && "thing".equals(levels[4]) && "event".equals(levels[5])
                && "post".equals(levels[7])) {
            return "host-event";
        }
        if (levels.length == 6 && "sub".equals(levels[2])
                && "shadow".equals(levels[4]) && "report".equals(levels[5])) {
            return "host-shadow";
        }
        if (levels.length == 5 && "sub".equals(levels[2])
                && "status".equals(levels[4])) {
            return "host-device-status";
        }
        if (levels.length == 4 && "ota".equals(levels[2])
                && "progress".equals(levels[3])) {
            return "ota-progress";
        }
        if (levels.length == 6 && "sub".equals(levels[2])
                && "ota".equals(levels[4]) && "progress".equals(levels[5])) {
            return "host-ota-progress";
        }
        return null;
    }

    private void handleHosted(String kind, String tenantId, String hostId, String deviceId,
                              String topic, byte[] payload) {
        if (!hostDeviceAccessService.isBound(hostId, tenantId, deviceId)) {
            meterRegistry.counter("coherer.mqtt.hosted_rejected").increment();
            log.warn("主机越权上报子设备数据 hostId={} deviceId={} topic={}",
                    hostId, deviceId, topic);
            return;
        }
        try {
            hostStatusService.heartbeat(hostId);
            switch (kind) {
                case "host-telemetry" -> {
                    telemetryService.ingest(tenantId, deviceId, payload);
                    deviceStatusService.heartbeat(deviceId);
                }
                case "host-shadow" -> {
                    shadowService.onReported(tenantId, deviceId, payload);
                    deviceStatusService.heartbeat(deviceId);
                }
                case "host-device-status" -> deviceStatusService.onStatus(tenantId, deviceId,
                        new String(payload, StandardCharsets.UTF_8));
                case "host-event" -> {
                    String[] levels = topic.split("/");
                    eventService.ingest(tenantId, deviceId, levels[6], payload);
                    deviceStatusService.heartbeat(deviceId);
                }
                case "host-ota-progress" -> {
                    otaProgressService.onProgress(deviceId, payload);
                    deviceStatusService.heartbeat(deviceId);
                }
                default -> {
                }
            }
        } catch (Exception e) {
            log.warn("主机子设备数据处理失败 topic={} err={}", topic, e.getMessage());
        }
    }

    private void handle(String kind, String tenantId, String deviceId, String topic, byte[] payload) {
        try {
            switch (kind) {
                case "telemetry" -> {
                    // 先入库再续期：在线状态是旁路，任何异常都不能影响数据
                    telemetryService.ingest(tenantId, deviceId, payload);
                    deviceStatusService.heartbeat(deviceId);
                }
                case "shadow" -> {
                    shadowService.onReported(tenantId, deviceId, payload);
                    deviceStatusService.heartbeat(deviceId);
                }
                case "status" -> deviceStatusService.onStatus(tenantId, deviceId,
                        new String(payload, StandardCharsets.UTF_8));
                case "command-reply" -> commandReplyService.onReply(tenantId, deviceId, payload);
                case "event" -> {
                    String[] levels = topic.split("/");
                    eventService.ingest(tenantId, deviceId, levels[4], payload);
                    deviceStatusService.heartbeat(deviceId);
                }
                case "ota-progress" -> otaProgressService.onProgress(deviceId, payload);
                case "host-status" -> hostStatusService.onStatus(tenantId, deviceId,
                        new String(payload, StandardCharsets.UTF_8));
                case "host-command-reply" -> {
                    String[] levels = topic.split("/");
                    commandReplyService.onReply(tenantId, levels[3], payload);
                    hostStatusService.heartbeat(levels[1]);
                }
                default -> {
                }
            }
        } catch (Exception e) {
            log.warn("MQTT 消息处理失败 topic={} err={}", topic, e.getMessage());
        }
    }
}
