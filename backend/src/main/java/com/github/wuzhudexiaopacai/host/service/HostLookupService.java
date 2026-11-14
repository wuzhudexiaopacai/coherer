package com.github.wuzhudexiaopacai.host.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.wuzhudexiaopacai.host.entity.IotHost;
import com.github.wuzhudexiaopacai.host.mapper.IotHostMapper;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 主机热数据查询：MQTT 认证/ACL 高频按 hostId 查询。 */
@Service
@RequiredArgsConstructor
public class HostLookupService {

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
        return byHostId.get(hostId, id -> Optional.ofNullable(
                        hostMapper.selectOne(Wrappers.<IotHost>lambdaQuery()
                                .eq(IotHost::getHostId, id)
                                .last("limit 1"))))
                .orElse(null);
    }

    public IotHost getById(Long id) {
        if (id == null) {
            return null;
        }
        return byId.get(id, key -> Optional.ofNullable(hostMapper.selectById(key))).orElse(null);
    }

    public void invalidate(IotHost host) {
        if (host == null) {
            return;
        }
        byHostId.invalidate(host.getHostId());
        byId.invalidate(host.getId());
    }
}
