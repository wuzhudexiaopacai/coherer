package com.github.wuzhudexiaopacai.ota.service;

import com.github.wuzhudexiaopacai.ota.entity.OtaTask;

/** OTA 重试策略：attempt 从 1 开始，attempt < maxAttempts 时允许再试。 */
public final class OtaRetryPolicy {

    private OtaRetryPolicy() {
    }

    public static boolean canRetry(OtaTask task) {
        int attempt = task.getAttempt() == null ? 1 : task.getAttempt();
        int max = task.getMaxAttempts() == null ? 1 : task.getMaxAttempts();
        return attempt < max;
    }

    /** 线性退避：第 n 次失败后等待 base * n 秒。 */
    public static long backoffSeconds(int nextAttempt, long baseSeconds) {
        return Math.max(1, baseSeconds) * Math.max(1, nextAttempt - 1);
    }
}
