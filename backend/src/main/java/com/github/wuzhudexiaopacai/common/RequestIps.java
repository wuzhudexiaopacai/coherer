package com.github.wuzhudexiaopacai.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * 解析真实客户端 IP。
 *
 * <p>部署在反向代理/负载均衡后面时，{@code getRemoteAddr()} 只会得到网关地址，
 * 因此优先使用 {@code X-Forwarded-For} 的首个地址，其次 {@code X-Real-IP}。</p>
 *
 * <p>注意：这两个头可被客户端伪造，仅应在可信代理（网关会覆盖该头）后使用；
 * 用于审计展示足够，不能作为安全边界。</p>
 */
public final class RequestIps {

    private RequestIps() {
    }

    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            String first = forwarded.split(",")[0].trim();
            if (StringUtils.hasText(first) && !"unknown".equalsIgnoreCase(first)) {
                return truncate(first);
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return truncate(realIp.trim());
        }
        return truncate(request.getRemoteAddr());
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        // 与 audit_log.ip VARCHAR(64) 对齐
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
