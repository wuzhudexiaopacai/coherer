package com.github.wuzhudexiaopacai.internal.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * MQTT 主题授权（EMQX HTTP ACL 回调）：
 *
 * <ul>
 *   <li>后端服务账号：放行全部（消费共享订阅、下发影子）</li>
 *   <li>浏览器 Web 账号：只读订阅遥测与状态主题</li>
 *   <li>设备：只能发布/订阅自己 tenant/deviceId 下的主题</li>
 * </ul>
 *
 * 授权结果缓存 60 秒，减少 EMQX 每条消息的回调开销。
 */
@Service
@RequiredArgsConstructor
public class MqttAclService {

    private static final Logger log = LoggerFactory.getLogger(MqttAclService.class);

    private final DeviceLookupService deviceLookupService;

    @Value("${coherer.mqtt.ingest.client-id}")
    private String ingestClientId;

    @Value("${coherer.mqtt.ingest.username}")
    private String ingestUsername;

    private final Cache<String, Boolean> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(50_000)
            .build();

    public boolean authorize(String clientId, String username, String topic, String action) {
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(topic)) {
            return false;
        }
        String key = clientId + "|" + username + "|" + topic + "|" + action;
        return cache.get(key, ignored -> doAuthorize(clientId, username, topic, action));
    }

    private boolean doAuthorize(String clientId, String username, String topic, String action) {
        // 服务账号：全量放行
        if (ingestClientId.equals(clientId) && ingestUsername.equals(username)) {
            return true;
        }

        // 浏览器账号：只读订阅遥测/状态
        if (clientId.startsWith("web-") && username != null && username.startsWith("web:")) {
            if (!"subscribe".equals(action)) {
                return false;
            }
            return isTelemetryTopic(topic) || isStatusTopic(topic);
        }

        // 设备账号：仅限自身主题空间
        Device device = deviceLookupService.getByDeviceId(clientId);
        if (device == null) {
            log.debug("ACL 拒绝：未知设备 clientId={} topic={}", clientId, topic);
            return false;
        }
        String[] levels = topic.split("/");
        if (levels.length < 2) {
            return false;
        }
        String tenant = StringUtils.hasText(device.getTenantId()) ? device.getTenantId() : "default";
        boolean allowed = tenant.equals(levels[0]) && clientId.equals(levels[1]);
        if (!allowed) {
            log.debug("ACL 拒绝：设备越权 clientId={} topic={} action={}", clientId, topic, action);
        }
        return allowed;
    }

    private boolean isTelemetryTopic(String topic) {
        String[] levels = topic.split("/");
        return levels.length == 5
                && "default".equals(levels[0])
                && "thing".equals(levels[2])
                && "property".equals(levels[3])
                && "report".equals(levels[4]);
    }

    private boolean isStatusTopic(String topic) {
        String[] levels = topic.split("/");
        return levels.length == 3
                && "default".equals(levels[0])
                && "status".equals(levels[2]);
    }
}
