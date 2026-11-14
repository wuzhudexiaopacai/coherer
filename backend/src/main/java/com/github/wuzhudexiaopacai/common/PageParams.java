package com.github.wuzhudexiaopacai.common;

/** 分页参数收敛，避免 size 无上限导致拖库/DoS。 */
public final class PageParams {

    private PageParams() {
    }

    public static long current(long current) {
        return Math.max(current, 1);
    }

    public static long size(long size, long max) {
        return Math.min(Math.max(size, 1), max);
    }
}
