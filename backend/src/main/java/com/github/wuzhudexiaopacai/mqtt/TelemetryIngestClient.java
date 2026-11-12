package com.github.wuzhudexiaopacai.mqtt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wuzhudexiaopacai.telemetry.service.TelemetryService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 遥测消费者（Eclipse Paho 实现）：以共享订阅方式消费所有设备的属性上报。
 *
 * <p>共享订阅 {@code $share/coherer/...} 保证多实例部署时同一条消息只被一个实例消费，
 * 服务可水平扩展。</p>
 */
@Component
@RequiredArgsConstructor
public class TelemetryIngestClient {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestClient.class);
    private static final String TOPIC_FILTER = "$share/coherer/+/+/thing/property/report";

    private final ObjectMapper objectMapper;
    private final TelemetryService telemetryService;

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

    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private MqttAsyncClient client;

    @PostConstruct
    void init() {
        try {
            client = new MqttAsyncClient(
                    "tcp://" + brokerHost + ":" + brokerPort,
                    clientId,
                    new MemoryPersistence());
            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    log.info("遥测消费者已连接 EMQX {}（reconnect={}）", serverURI, reconnect);
                    subscribe();
                }

                @Override
                public void connectionLost(Throwable cause) {
                    log.warn("与 EMQX 连接断开，等待自动重连: {}",
                            cause == null ? "unknown" : cause.getMessage());
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    onMessage(topic, message.getPayload());
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // 只订阅不发布
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

    /** 初始连接失败或断线后兜底重连（Paho 自动重连只在连接成功过一次后生效）。 */
    @Scheduled(fixedDelay = 10_000)
    void ensureConnected() {
        if (client != null && !client.isConnected() && !connecting.get()) {
            connect();
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
            client.connect(options).waitForCompletion(10_000);
        } catch (MqttException e) {
            log.warn("接入 EMQX 失败: {}", e.getMessage());
        } finally {
            connecting.set(false);
        }
    }

    private void subscribe() {
        try {
            client.subscribe(TOPIC_FILTER, 1);
            log.info("已共享订阅 {}", TOPIC_FILTER);
        } catch (MqttException e) {
            log.error("共享订阅失败: {}", e.getMessage());
        }
    }

    private void onMessage(String topic, byte[] payload) {
        try {
            String[] levels = topic.split("/");
            if (levels.length < 5) {
                return;
            }
            String tenantId = levels[0];
            String deviceId = levels[1];

            JsonNode root = objectMapper.readTree(payload);
            if (!root.path("params").isObject()) {
                log.debug("遥测缺少 params，丢弃 topic={}", topic);
                return;
            }
            long ts = root.path("ts").asLong(System.currentTimeMillis());
            Map<String, Object> params = objectMapper.convertValue(
                    root.path("params"), new TypeReference<Map<String, Object>>() {
                    });
            telemetryService.ingest(tenantId, deviceId, ts, params);
        } catch (Exception e) {
            log.warn("遥测消息处理失败 topic={} err={}", topic, e.getMessage());
        }
    }
}
