package com.github.wuzhudexiaopacai.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.wuzhudexiaopacai.auth.entity.SysUser;
import com.github.wuzhudexiaopacai.auth.mapper.SysUserMapper;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * 解析当前登录用户的数据范围。
 *
 * <p>服务端是数据权限的唯一裁决者：客户端传入的 spaceId 只会被收窄，
 * 永远不会放大用户的实际范围。</p>
 */
@Service
@RequiredArgsConstructor
public class DataScopeService {

    private final SysUserMapper userMapper;
    private final SpaceService spaceService;

    /** 用户空间范围缓存：REST 高频调用，30 秒内复用；用户变更后最迟 30 秒生效。 */
    private final Cache<String, Optional<SysUser>> userCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(10_000)
            .build();

    public DataScope current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return DataScope.of(null, List.of());
        }
        return of(authentication.getName());
    }

    public DataScope of(String username) {
        SysUser user = userCache.get(username, name -> Optional.ofNullable(
                        userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                                .eq(SysUser::getUsername, name)
                                .last("limit 1"))))
                .orElse(null);
        // 用户不存在或已禁用：返回空范围，后续查询自然为空
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            return DataScope.of(null, List.of());
        }
        if (user.getSpaceId() == null) {
            return DataScope.all();
        }
        return DataScope.of(user.getSpaceId(), spaceService.descendantIds(user.getSpaceId()));
    }

    /**
     * 将客户端请求的 spaceId 解析为实际查询的空间集合。
     *
     * @return null 表示不限制（仅全平台用户且未指定空间时）
     * @throws BizException 请求的空间超出用户数据范围（403）
     */
    public List<Long> resolveSpaceIds(DataScope scope, Long requestedSpaceId) {
        if (requestedSpaceId == null) {
            return scope.unrestricted() ? null : scope.spaceIds();
        }
        if (!scope.allows(requestedSpaceId)) {
            throw new BizException(403, "无权访问该空间");
        }
        return spaceService.descendantIds(requestedSpaceId);
    }

    /** 常用组合：当前用户 + 客户端 spaceId。 */
    public List<Long> resolveSpaceIds(Long requestedSpaceId) {
        return resolveSpaceIds(current(), requestedSpaceId);
    }

    /** 校验目标空间在数据范围内，越界返回 403。 */
    public void requireSpace(DataScope scope, Long spaceId) {
        if (!scope.allows(spaceId)) {
            throw new BizException(403, "无权访问该空间");
        }
    }

    public void invalidate(String username) {
        userCache.invalidate(username);
    }
}
