package com.github.wuzhudexiaopacai.tenant.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.tenant.entity.Tenant;
import com.github.wuzhudexiaopacai.tenant.mapper.TenantMapper;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 租户热数据查询：认证、JWT 校验、默认项目/空间解析。 */
@Service
@RequiredArgsConstructor
public class TenantLookupService {

    private final TenantMapper tenantMapper;

    private final Cache<String, Optional<Tenant>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(10_000)
            .build();

    public Tenant get(String tenantId) {
        if (tenantId == null) {
            return null;
        }
        return cache.get(tenantId, id -> Optional.ofNullable(tenantMapper.selectById(id)))
                .orElse(null);
    }

    public Tenant require(String tenantId) {
        Tenant tenant = get(tenantId);
        if (tenant == null) {
            throw new BizException(404, "租户不存在");
        }
        return tenant;
    }

    public Tenant requireActive(String tenantId) {
        Tenant tenant = require(tenantId);
        if (!"ACTIVE".equals(tenant.getStatus())) {
            throw new BizException(403, "租户已禁用");
        }
        return tenant;
    }

    public boolean isActive(String tenantId) {
        Tenant tenant = get(tenantId);
        return tenant != null && "ACTIVE".equals(tenant.getStatus());
    }

    public Long defaultProjectId(String tenantId) {
        Tenant tenant = requireActive(tenantId);
        if (tenant.getDefaultProjectId() == null) {
            throw new BizException(500, "租户缺少默认项目配置");
        }
        return tenant.getDefaultProjectId();
    }

    public Long defaultSpaceId(String tenantId) {
        Tenant tenant = requireActive(tenantId);
        if (tenant.getDefaultSpaceId() == null) {
            throw new BizException(500, "租户缺少默认空间配置");
        }
        return tenant.getDefaultSpaceId();
    }

    public void invalidate(String tenantId) {
        if (tenantId != null) {
            cache.invalidate(tenantId);
        }
    }

    public long count() {
        Long count = tenantMapper.selectCount(Wrappers.<Tenant>lambdaQuery());
        return count == null ? 0 : count;
    }
}
