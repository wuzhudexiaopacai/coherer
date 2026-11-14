package com.github.wuzhudexiaopacai.internal.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.wuzhudexiaopacai.auth.entity.SysUser;
import com.github.wuzhudexiaopacai.auth.mapper.SysUserMapper;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.host.entity.IotHost;
import com.github.wuzhudexiaopacai.host.service.HostLookupService;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import java.time.Duration;
import java.util.List;
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
    private final HostLookupService hostLookupService;
    private final SysUserMapper userMapper;
    private final SpaceService spaceService;

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

        // 浏览器账号：只读订阅遥测/状态，并按用户空间数据范围限制
        if (clientId.startsWith("web-") && username != null && username.startsWith("web:")) {
            return authorizeWeb(username, topic, action);
        }

        // 边缘主机：仅允许自身 tenant/hostId 下的 host/ 与 sub/ 主题
        if (clientId.startsWith("host_")) {
            return authorizeHost(clientId, topic);
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

    private boolean authorizeHost(String hostId, String topic) {
        IotHost host = hostLookupService.getByHostId(hostId);
        if (host == null || "DISABLED".equals(host.getStatus())) {
            return false;
        }
        String[] levels = topic.split("/");
        if (levels.length < 3) {
            return false;
        }
        String tenant = StringUtils.hasText(host.getTenantId()) ? host.getTenantId() : "default";
        if (!tenant.equals(levels[0]) || !hostId.equals(levels[1])) {
            return false;
        }
        return "host".equals(levels[2]) || "sub".equals(levels[2]);
    }

    private boolean authorizeWeb(String username, String topic, String action) {
        if (!"subscribe".equals(action) || !(isTelemetryTopic(topic) || isStatusTopic(topic))) {
            return false;
        }
        String[] parts = username.split(":");
        if (parts.length != 3) {
            return false;
        }
        long userId;
        try {
            userId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            return false;
        }
        // 未设置空间 = 全平台数据范围
        if (user.getSpaceId() == null) {
            return true;
        }
        // 受限用户：禁止通配符订阅，只允许其空间（含子空间）下的具体设备
        String[] levels = topic.split("/");
        String deviceId = levels.length >= 2 ? levels[1] : null;
        if (deviceId == null || "+".equals(deviceId) || "#".equals(deviceId)) {
            return false;
        }
        Device device = deviceLookupService.getByDeviceId(deviceId);
        if (device == null || device.getSpaceId() == null) {
            return false;
        }
        List<Long> allowedSpaces = spaceService.descendantIds(user.getSpaceId());
        return allowedSpaces.contains(device.getSpaceId());
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
