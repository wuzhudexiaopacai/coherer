package com.github.wuzhudexiaopacai.security;

import com.github.wuzhudexiaopacai.tenant.service.TenantLookupService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 解析 Bearer JWT，写入 SecurityContext 与租户上下文；校验用户/租户状态与令牌版本。 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final TokenSessionValidator sessionValidator;
    private final TenantLookupService tenantLookupService;

    public JwtAuthFilter(JwtService jwtService,
                         TokenSessionValidator sessionValidator,
                         TenantLookupService tenantLookupService) {
        this.jwtService = jwtService;
        this.sessionValidator = sessionValidator;
        this.tenantLookupService = tenantLookupService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            authenticate(request);
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void authenticate(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return;
        }
        try {
            Claims claims = jwtService.parseAccess(header.substring(7));
            String tenantId = claims.get("tenant", String.class);
            if (tenantId == null || tenantId.isBlank()
                    || !tenantLookupService.isActive(tenantId)) {
                throw new IllegalArgumentException("租户不可用");
            }
            Integer tokenVersion = claims.get("ver", Integer.class);
            // 先建立租户上下文：会话校验查询 sys_user 也受租户拦截器约束
            TenantContext.set(tenantId);
            if (!sessionValidator.isValid(tenantId, claims.getSubject(), tokenVersion)) {
                TenantContext.clear();
                SecurityContextHolder.clearContext();
                return;
            }
            String role = claims.get("role", String.class);
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            if (Boolean.TRUE.equals(claims.get("platform", Boolean.class))) {
                authorities.add(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
            }
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            claims.getSubject(), null, authorities);
            authentication.setDetails(claims);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (Exception e) {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
