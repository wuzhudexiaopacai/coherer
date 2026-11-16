package com.github.wuzhudexiaopacai.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.wuzhudexiaopacai.auth.dto.LoginRequest;
import com.github.wuzhudexiaopacai.auth.dto.RefreshRequest;
import com.github.wuzhudexiaopacai.auth.entity.SysUser;
import com.github.wuzhudexiaopacai.auth.mapper.SysUserMapper;
import com.github.wuzhudexiaopacai.auth.vo.AuthResponse;
import com.github.wuzhudexiaopacai.auth.vo.UserInfoVO;
import com.github.wuzhudexiaopacai.audit.AuditLogService;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.security.JwtService;
import com.github.wuzhudexiaopacai.security.RefreshTokenStore;
import com.github.wuzhudexiaopacai.security.TenantContext;
import com.github.wuzhudexiaopacai.security.TokenSessionValidator;
import com.github.wuzhudexiaopacai.tenant.entity.Tenant;
import com.github.wuzhudexiaopacai.tenant.service.TenantLookupService;
import io.jsonwebtoken.Claims;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttemptService;
    private final AuditLogService auditLogService;
    private final TokenSessionValidator sessionValidator;
    private final RefreshTokenStore refreshTokenStore;
    private final TenantLookupService tenantLookupService;
    private final MeterRegistry meterRegistry;

    public AuthResponse login(LoginRequest request, String clientIp) {
        String tenantId = request.getTenantCode().trim();
        Tenant tenant = tenantLookupService.require(tenantId);
        if (!"ACTIVE".equals(tenant.getStatus())) {
            throw new BizException(403, "租户已禁用");
        }
        return TenantContext.callAs(tenantId, () -> doLogin(request, tenant, clientIp));
    }

    private AuthResponse doLogin(LoginRequest request, Tenant tenant, String clientIp) {
        String attemptKey = tenant.getId() + "|" + request.getUsername();
        if (loginAttemptService.isBlocked(attemptKey, clientIp)) {
            auditLogService.recordAsFailure(request.getUsername(), clientIp, "LOGIN_BLOCKED",
                    "auth", Map.of("reason", "rate_limited", "tenant", tenant.getId()));
            throw new BizException(429, "登录尝试过于频繁，请稍后再试");
        }
        SysUser user = findByUsername(request.getUsername());
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            loginAttemptService.recordFailure(attemptKey, clientIp);
            meterRegistry.counter("coherer.auth.login.failures").increment();
            auditLogService.recordAsFailure(request.getUsername(), clientIp, "LOGIN_FAILURE",
                    "auth", Map.of("reason", "bad_credentials", "tenant", tenant.getId()));
            throw new BizException(401, "用户名或密码错误");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            auditLogService.recordAsFailure(request.getUsername(), clientIp, "LOGIN_FAILURE",
                    "auth", Map.of("reason", "disabled", "tenant", tenant.getId()));
            throw new BizException(403, "账号已禁用");
        }
        loginAttemptService.reset(attemptKey, clientIp);
        auditLogService.recordAs(user.getUsername(), clientIp, "LOGIN_SUCCESS", "auth",
                Map.of("role", user.getRole(), "tenant", tenant.getId()));
        return buildResponse(user, tenant);
    }

    public AuthResponse refresh(RefreshRequest request) {
        Claims claims;
        try {
            claims = jwtService.parseRefresh(request.getRefreshToken());
        } catch (Exception e) {
            throw new BizException(401, "刷新令牌无效或已过期");
        }
        String tenantId = claims.get("tenant", String.class);
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(401, "刷新令牌缺少租户信息");
        }
        Tenant tenant = tenantLookupService.requireActive(tenantId);
        return TenantContext.callAs(tenantId, () -> doRefresh(claims, tenant));
    }

    private AuthResponse doRefresh(Claims claims, Tenant tenant) {
        SysUser user = findByUsername(claims.getSubject());
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new BizException(401, "用户不可用");
        }
        int current = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        Integer tokenVersion = claims.get("ver", Integer.class);
        if ((tokenVersion == null ? 0 : tokenVersion) != current) {
            throw new BizException(401, "登录状态已失效，请重新登录");
        }
        // 一次性消费旧 refresh jti：同一刷新令牌只能用一次，防止重放
        if (!refreshTokenStore.consume(tenant.getId(), claims.getId(), user.getUsername())) {
            throw new BizException(401, "刷新令牌已使用或已失效，请重新登录");
        }
        return buildResponse(user, tenant);
    }

    /** 退出登录：提升令牌版本，使该用户所有已签发 token 失效。 */
    public void logout(String username) {
        String tenantId = TenantContext.requireTenant();
        SysUser user = findByUsername(username);
        if (user == null) {
            return;
        }
        user.setTokenVersion((user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1);
        userMapper.updateById(user);
        sessionValidator.invalidate(tenantId, username);
        auditLogService.record("LOGOUT", "auth", Map.of());
    }

    public UserInfoVO me(String username) {
        SysUser user = findByUsername(username);
        if (user == null) {
            throw new BizException(401, "用户不存在");
        }
        return toUserInfo(user);
    }

    private SysUser findByUsername(String username) {
        return userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username)
                .last("limit 1"));
    }

    private AuthResponse buildResponse(SysUser user, Tenant tenant) {
        String refreshJti = refreshTokenStore.issue(
                tenant.getId(), user.getUsername(), jwtService.refreshTtlSeconds());
        return new AuthResponse(
                jwtService.issueAccess(user),
                jwtService.issueRefresh(user, refreshJti),
                jwtService.accessTtlSeconds(),
                toUserInfo(user, tenant));
    }

    private UserInfoVO toUserInfo(SysUser user) {
        return toUserInfo(user, tenantLookupService.get(user.getTenantId()));
    }

    private UserInfoVO toUserInfo(SysUser user, Tenant tenant) {
        UserInfoVO vo = new UserInfoVO();
        vo.setId(user.getId());
        vo.setTenantId(user.getTenantId());
        vo.setTenantName(tenant == null ? null : tenant.getName());
        vo.setPlatformAdmin(user.getPlatformAdmin() != null && user.getPlatformAdmin() == 1);
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setRole(user.getRole());
        vo.setSpaceId(user.getSpaceId());
        return vo;
    }
}
