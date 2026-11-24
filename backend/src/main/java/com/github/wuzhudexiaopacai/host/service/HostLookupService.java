package com.github.wuzhudexiaopacai.host.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.wuzhudexiaopacai.host.entity.IotHost;
import com.github.wuzhudexiaopacai.host.mapper.IotHostMapper;
import com.github.wuzhudexiaopacai.security.TenantContext;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 主机热数据查询：MQTT 认证/ACL 高频按 hostId 查询。
 *
 * <p>与设备缓存同理：缓存命中不会走 SQL，租户拦截器不生效，
 * 因此读取后必须校验主机租户与当前上下文一致；系统路径显式 bypass。</p>
 */
@Service
@RequiredArgsConstructor
public class HostLookupService {

    private static final Logger log = LoggerFactory.getLogger(HostLookupService.class);

    private final IotHostMapper hostMapper;

    private final Cache<String, Optional<IotHost>> byHostId = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(10_000)
            .build();
    private final Cache<Long, Optional<IotHost>> byId = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(10_000)
            .build();

    public IotHost getByHostId(String hostId) {
        IotHost host = byHostId.get(hostId, id -> Optional.ofNullable(
                        hostMapper.selectOne(Wrappers.<IotHost>lambdaQuery()
                                .eq(IotHost::getHostId, id)
                                .last("limit 1"))))
                .orElse(null);
        return visible(host);
    }

    public IotHost getById(Long id) {
        if (id == null) {
            return null;
        }
        IotHost host = byId.get(id, key -> Optional.ofNullable(hostMapper.selectById(key)))
                .orElse(null);
        return visible(host);
    }

    public void invalidate(IotHost host) {
        if (host == null) {
            return;
        }
        byHostId.invalidate(host.getHostId());
        byId.invalidate(host.getId());
    }

    private IotHost visible(IotHost host) {
        if (host == null || TenantContext.bypassed()) {
            return host;
        }
        String tenant = TenantContext.requireTenant();
        if (!tenant.equals(host.getTenantId())) {
            log.debug("拒绝跨租户主机缓存命中 hostId={} hostTenant={} requestTenant={}",
                    host.getHostId(), host.getTenantId(), tenant);
            return null;
        }
        return host;
    }
}
