package com.github.wuzhudexiaopacai.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.wuzhudexiaopacai.auth.dto.PasswordResetRequest;
import com.github.wuzhudexiaopacai.auth.dto.UserCreateRequest;
import com.github.wuzhudexiaopacai.auth.dto.UserUpdateRequest;
import com.github.wuzhudexiaopacai.auth.entity.SysUser;
import com.github.wuzhudexiaopacai.auth.mapper.SysUserMapper;
import com.github.wuzhudexiaopacai.auth.vo.UserVO;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.common.PageResult;
import com.github.wuzhudexiaopacai.security.DataScopeService;
import com.github.wuzhudexiaopacai.security.TokenSessionValidator;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户管理（ADMIN）。
 *
 * <p>安全约束：不能删除/禁用自己；不能移除最后一个可用管理员，
 * 避免把平台锁死在无人可管理的状态。</p>
 */
@Service
@RequiredArgsConstructor
public class UserService extends ServiceImpl<SysUserMapper, SysUser> {

    private final PasswordEncoder passwordEncoder;
    private final SpaceService spaceService;
    private final TokenSessionValidator sessionValidator;
    private final DataScopeService dataScopeService;

    public PageResult<UserVO> page(long current, long size, String keyword) {
        Page<SysUser> page = page(new Page<>(current, size),
                Wrappers.<SysUser>lambdaQuery()
                        .and(StringUtils.hasText(keyword),
                                w -> w.like(SysUser::getUsername, keyword)
                                        .or()
                                        .like(SysUser::getNickname, keyword))
                        .orderByAsc(SysUser::getCreatedAt));
        return PageResult.of(page.convert(this::toVO));
    }

    @Transactional(rollbackFor = Exception.class)
    public UserVO create(UserCreateRequest request) {
        Long exists = baseMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, request.getUsername()));
        if (exists != null && exists > 0) {
            throw new BizException(409, "用户名已存在");
        }
        validateSpace(request.getSpaceId());

        SysUser user = new SysUser();
        user.setTenantId(com.github.wuzhudexiaopacai.security.TenantContext.requireTenant());
        user.setPlatformAdmin(0);
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname());
        user.setRole(request.getRole() == null ? "VIEWER" : request.getRole());
        user.setSpaceId(request.getSpaceId());
        user.setStatus("ACTIVE");
        user.setTokenVersion(0);
        save(user);
        return toVO(user);
    }

    @Transactional(rollbackFor = Exception.class)
    public UserVO update(Long id, UserUpdateRequest request, String operator) {
        SysUser user = requireUser(id);
        boolean losingAdmin = "ADMIN".equals(user.getRole())
                && request.getRole() != null && !"ADMIN".equals(request.getRole());
        boolean disabling = request.getStatus() != null && !"ACTIVE".equals(request.getStatus());
        if (disabling && user.getUsername().equals(operator)) {
            throw new BizException(409, "不能禁用当前登录账号");
        }
        if (losingAdmin && user.getUsername().equals(operator)) {
            throw new BizException(409, "不能降级当前登录账号");
        }
        if ("ADMIN".equals(user.getRole()) && (losingAdmin || disabling)) {
            requireOtherActiveAdmin(user.getId());
        }
        if (request.getSpaceId() != null) {
            Long spaceId = request.getSpaceId() == 0L ? null : request.getSpaceId();
            validateSpace(spaceId);
            user.setSpaceId(spaceId);
        }
        if (StringUtils.hasText(request.getNickname())) {
            user.setNickname(request.getNickname());
        }
        boolean revoke = false;
        if (StringUtils.hasText(request.getRole())
                && !request.getRole().equals(user.getRole())) {
            user.setRole(request.getRole());
            revoke = true;
        }
        if (StringUtils.hasText(request.getStatus())
                && !request.getStatus().equals(user.getStatus())) {
            user.setStatus(request.getStatus());
            revoke = true;
        }
        if (revoke) {
            // 角色/状态变化立即吊销旧 token，避免降级后仍保留管理员权限
            user.setTokenVersion((user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1);
        }
        updateById(user);
        if (revoke) {
            sessionValidator.invalidate(user.getTenantId(), user.getUsername());
        }
        // 数据范围可能已收窄/放大：立即失效 30 秒缓存，避免旧范围继续放行
        dataScopeService.invalidate(user.getUsername());
        return toVO(user);
    }

    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long id, PasswordResetRequest request) {
        SysUser user = requireUser(id);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        // 改密后所有旧会话失效
        user.setTokenVersion((user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1);
        updateById(user);
        sessionValidator.invalidate(user.getTenantId(), user.getUsername());
        dataScopeService.invalidate(user.getUsername());
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, String operator) {
        SysUser user = requireUser(id);
        if (user.getUsername().equals(operator)) {
            throw new BizException(409, "不能删除当前登录账号");
        }
        if ("ADMIN".equals(user.getRole()) && "ACTIVE".equals(user.getStatus())) {
            requireOtherActiveAdmin(user.getId());
        }
        removeById(id);
        sessionValidator.invalidate(user.getTenantId(), user.getUsername());
        dataScopeService.invalidate(user.getUsername());
    }

    private void requireOtherActiveAdmin(Long excludeId) {
        Long otherAdmins = baseMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getRole, "ADMIN")
                .eq(SysUser::getStatus, "ACTIVE")
                .ne(SysUser::getId, excludeId));
        if (otherAdmins == null || otherAdmins == 0) {
            throw new BizException(409, "平台至少需要保留一个可用管理员");
        }
    }

    private void validateSpace(Long spaceId) {
        if (spaceId != null && !spaceService.exists(spaceId)) {
            throw new BizException(404, "空间不存在");
        }
    }

    private SysUser requireUser(Long id) {
        SysUser user = getById(id);
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        return user;
    }

    private UserVO toVO(SysUser user) {
        UserVO vo = new UserVO();
        BeanUtils.copyProperties(user, vo);
        vo.setPlatformAdmin(user.getPlatformAdmin() != null && user.getPlatformAdmin() == 1);
        return vo;
    }
}
