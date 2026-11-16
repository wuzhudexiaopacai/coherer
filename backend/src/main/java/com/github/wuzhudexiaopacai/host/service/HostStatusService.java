package com.github.wuzhudexiaopacai.host.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.host.entity.IotHost;
import com.github.wuzhudexiaopacai.host.mapper.IotHostMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 主机在线状态与级联。
 *
 * <p>在线标记写 Redis 并带 TTL，主机定期心跳（online 状态）续期；
 * TTL 过期后巡检把主机置为 OFFLINE，并级联子设备离线。
 * 这样即使 LWT 丢失或后端崩溃，也不会出现主机永久在线。</p>
 */
@Service
@RequiredArgsConstructor
public class HostStatusService {

    private static final Logger log = LoggerFactory.getLogger(HostStatusService.class);
    private static final String REDIS_KEY_PREFIX = "coherer:status:host:";

    private final IotHostMapper hostMapper;
    private final DeviceMapper deviceMapper;
    private final HostLookupService hostLookupService;
    private final DeviceLookupService deviceLookupService;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${coherer.host.online-ttl-seconds:180}")
    private long onlineTtlSeconds;

    public void onStatus(String tenantId, String hostId, String payload) {
        String status = payload == null ? "" : payload.trim().toLowerCase();
        boolean online = "online".equals(status);
        if (!online && !"offline".equals(status)) {
            log.debug("忽略非法主机状态 hostId={} payload={}", hostId, payload);
            return;
        }
        IotHost host = hostLookupService.getByHostId(hostId);
        if (host == null) {
            log.debug("收到未注册主机的状态 hostId={}", hostId);
            return;
        }
        String tenant = host.getTenantId() == null ? "default" : host.getTenantId();
        if (!tenant.equals(tenantId)) {
            log.warn("主机状态租户不匹配 hostId={} topicTenant={}", hostId, tenantId);
            return;
        }

        if (online) {
            markOnline(host);
        } else {
            markOffline(host, true);
        }
    }

    /**
     * 主机消息存活续期：命令回执等消息也证明主机在线。
     * Redis 标记存在时只刷 TTL，不写数据库。
     */
    public void heartbeat(String hostId) {
        try {
            String key = REDIS_KEY_PREFIX + hostId;
            String state = redisTemplate.opsForValue().get(key);
            if ("online".equals(state)) {
                redisTemplate.expire(key, Duration.ofSeconds(onlineTtlSeconds));
                return;
            }
            IotHost host = hostLookupService.getByHostId(hostId);
            if (host != null) {
                markOnline(host);
            }
        } catch (Exception e) {
            // 在线状态是旁路信息，Redis 故障不能阻塞命令回执/数据处理
            meterRegistry.counter("coherer.host.status.redis_error").increment();
            log.warn("主机心跳续期失败（忽略） hostId={} err={}", hostId, e.getMessage());
        }
    }

    /** 定时回收 TTL 过期但库中仍为 ONLINE 的主机。 */
    @Scheduled(fixedDelayString = "${coherer.host.online-sweep-interval-ms:60000}",
            initialDelayString = "${coherer.host.online-sweep-initial-delay-ms:30000}")
    public void sweepStaleOnline() {
        com.github.wuzhudexiaopacai.security.TenantContext.runBypass(this::doSweepStaleOnline);
    }

    private void doSweepStaleOnline() {
        List<IotHost> onlineHosts;
        try {
            onlineHosts = hostMapper.selectList(Wrappers.<IotHost>lambdaQuery()
                    .select(IotHost::getHostId)
                    .eq(IotHost::getStatus, "ONLINE"));
        } catch (Exception e) {
            log.warn("主机在线巡检查询失败: {}", e.getMessage());
            return;
        }
        if (onlineHosts.isEmpty()) {
            return;
        }
        int marked = 0;
        for (IotHost host : onlineHosts) {
            String state;
            try {
                state = redisTemplate.opsForValue()
                        .get(REDIS_KEY_PREFIX + host.getHostId());
            } catch (Exception e) {
                // Redis 整体不可用时无法判断，宁可本轮不回收也不能误判全量离线
                meterRegistry.counter("coherer.host.status.redis_error").increment();
                log.warn("主机在线巡检因 Redis 不可用中止: {}", e.getMessage());
                return;
            }
            if ("online".equals(state)) {
                continue;
            }
            int updated = hostMapper.update(null, Wrappers.<IotHost>lambdaUpdate()
                    .eq(IotHost::getHostId, host.getHostId())
                    .eq(IotHost::getStatus, "ONLINE")
                    .set(IotHost::getStatus, "OFFLINE"));
            if (updated > 0) {
                marked++;
                hostLookupService.invalidate(host);
                cascadeChildrenOffline(host);
                setOfflineMarker(host.getHostId());
            }
        }
        if (marked > 0) {
            meterRegistry.counter("coherer.host.status.stale_offline").increment(marked);
            log.info("主机状态巡检：{} 台主机因心跳超时置为 OFFLINE", marked);
        }
    }

    private void markOnline(IotHost host) {
        int updated = hostMapper.update(null, Wrappers.<IotHost>lambdaUpdate()
                .eq(IotHost::getHostId, host.getHostId())
                .ne(IotHost::getStatus, "DISABLED")
                .set(IotHost::getStatus, "ONLINE")
                .set(IotHost::getLastOnlineAt, OffsetDateTime.now()));
        if (updated > 0) {
            hostLookupService.invalidate(host);
        }
        try {
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + host.getHostId(), "online",
                    Duration.ofSeconds(onlineTtlSeconds));
        } catch (Exception e) {
            meterRegistry.counter("coherer.host.status.redis_error").increment();
            log.warn("主机在线标记写入失败 hostId={} err={}", host.getHostId(), e.getMessage());
        }
    }

    private void markOffline(IotHost host, boolean cascade) {
        int updated = hostMapper.update(null, Wrappers.<IotHost>lambdaUpdate()
                .eq(IotHost::getHostId, host.getHostId())
                .eq(IotHost::getStatus, "ONLINE")
                .set(IotHost::getStatus, "OFFLINE"));
        if (updated > 0) {
            hostLookupService.invalidate(host);
        }
        setOfflineMarker(host.getHostId());
        if (cascade) {
            cascadeChildrenOffline(host);
        }
    }

    private void setOfflineMarker(String hostId) {
        try {
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + hostId, "offline");
        } catch (Exception e) {
            meterRegistry.counter("coherer.host.status.redis_error").increment();
            log.warn("主机离线标记写入失败 hostId={} err={}", hostId, e.getMessage());
        }
    }

    private void cascadeChildrenOffline(IotHost host) {
        List<Device> children = deviceMapper.selectList(Wrappers.<Device>lambdaQuery()
                .select(Device::getDeviceId)
                .eq(Device::getHostId, host.getId())
                .eq(Device::getStatus, "ONLINE"));
        if (children.isEmpty()) {
            return;
        }
        int offline = deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                .eq(Device::getHostId, host.getId())
                .eq(Device::getStatus, "ONLINE")
                .set(Device::getStatus, "OFFLINE"));
        children.forEach(device -> deviceLookupService.invalidate(device.getDeviceId()));
        meterRegistry.counter("coherer.host.children_offline").increment(offline);
        log.info("主机离线级联：hostId={} 子设备 {} 台置为 OFFLINE", host.getHostId(), offline);
    }
}
