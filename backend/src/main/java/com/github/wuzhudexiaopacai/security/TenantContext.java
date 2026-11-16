package com.github.wuzhudexiaopacai.security;

import java.util.function.Supplier;

/**
 * 当前线程的租户上下文。
 *
 * <p>Web 请求由 JwtAuthFilter 设置，MQTT 消费按主题租户设置，系统巡检显式 bypass。
 * MyBatis 租户拦截器在上下文缺失时 fail-closed，避免漏查租户条件。</p>
 */
public final class TenantContext {

    private static final ThreadLocal<String> TENANT = new ThreadLocal<>();
    private static final ThreadLocal<Integer> BYPASS_DEPTH = new ThreadLocal<>();

    private TenantContext() {
    }

    public static String get() {
        return TENANT.get();
    }

    public static String requireTenant() {
        String tenant = TENANT.get();
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenant;
    }

    public static void set(String tenantId) {
        TENANT.set(tenantId);
    }

    public static void clear() {
        TENANT.remove();
        BYPASS_DEPTH.remove();
    }

    public static boolean bypassed() {
        Integer depth = BYPASS_DEPTH.get();
        return depth != null && depth > 0;
    }

    public static <T> T callAs(String tenantId, Supplier<T> action) {
        String previous = TENANT.get();
        TENANT.set(tenantId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                TENANT.remove();
            } else {
                TENANT.set(previous);
            }
        }
    }

    public static void runAs(String tenantId, Runnable action) {
        callAs(tenantId, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T callBypass(Supplier<T> action) {
        Integer previous = BYPASS_DEPTH.get();
        BYPASS_DEPTH.set(previous == null ? 1 : previous + 1);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                BYPASS_DEPTH.remove();
            } else {
                BYPASS_DEPTH.set(previous);
            }
        }
    }

    public static void runBypass(Runnable action) {
        callBypass(() -> {
            action.run();
            return null;
        });
    }
}
