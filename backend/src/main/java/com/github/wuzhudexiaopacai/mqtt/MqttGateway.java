package com.github.wuzhudexiaopacai.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wuzhudexiaopacai.device.status.DeviceStatusService;
import com.github.wuzhudexiaopacai.shadow.service.ShadowService;
import com.github.wuzhudexiaopacai.telemetry.service.TelemetryService;
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
public class MqttGateway {

    private static final Logger log = LoggerFactory.getLogger(MqttGateway.class);

    private static final String TELEMETRY_TOPIC = "$share/coherer/+/+/thing/property/report";
    private static final String SHADOW_REPORT_TOPIC = "$share/coherer/+/+/shadow/report";
    private static final String STATUS_TOPIC = "$share/coherer/+/+/status";

    private final TelemetryService telemetryService;
    private final ShadowService shadowService;
    private final DeviceStatusService deviceStatusService;
    private final ObjectMapper objectMapper;

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
                    new String[]{TELEMETRY_TOPIC, SHADOW_REPORT_TOPIC, STATUS_TOPIC},
                    new int[]{1, 1, 1});
            log.info("已共享订阅：遥测 / 影子上报 / 设备状态");
        } catch (MqttException e) {
            log.error("共享订阅失败: {}", e.getMessage());
        }
    }

    private void dispatch(String topic, byte[] payload) {
        String[] levels = topic.split("/");
        if (levels.length < 3) {
            return;
        }
        String tenantId = levels[0];
        String deviceId = levels[1];
        try {
            if (levels.length == 5 && "thing".equals(levels[2])
                    && "property".equals(levels[3]) && "report".equals(levels[4])) {
                telemetryService.ingest(tenantId, deviceId, payload);
            } else if (levels.length == 4 && "shadow".equals(levels[2])
                    && "report".equals(levels[3])) {
                shadowService.onReported(tenantId, deviceId, payload);
            } else if (levels.length == 3 && "status".equals(levels[2])) {
                deviceStatusService.onStatus(tenantId, deviceId,
                        new String(payload, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.warn("MQTT 消息处理失败 topic={} err={}", topic, e.getMessage());
        }
    }
}
