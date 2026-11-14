package com.github.wuzhudexiaopacai.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 保护内部端点：要求 X-Internal-Token 共享令牌。
 *
 * <p>受保护路径包括 EMQX 认证/ACL 回调（{@code /internal/**}）与 Prometheus 指标
 * （{@code /actuator/prometheus}）。指标端点包含 URI、错误率等运行信息，不应匿名可读。</p>
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Internal-Token";
    private static final String PROMETHEUS_PATH = "/actuator/prometheus";

    private final byte[] expectedToken;
    private final boolean protectMetrics;

    public InternalTokenFilter(String expectedToken) {
        this(expectedToken, true);
    }

    public InternalTokenFilter(String expectedToken, boolean protectMetrics) {
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
        this.protectMetrics = protectMetrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!protectedPath(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = request.getHeader(HEADER);
        if (token == null
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expectedToken)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":401,\"message\":\"invalid internal token\",\"data\":null}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean protectedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/internal/")) {
            return true;
        }
        return protectMetrics && PROMETHEUS_PATH.equals(uri);
    }
}
