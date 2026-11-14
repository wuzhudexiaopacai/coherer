package com.github.wuzhudexiaopacai.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PageParamsTest {

    @Test
    void current_belowOne_clampedToOne() {
        assertEquals(1, PageParams.current(0));
        assertEquals(1, PageParams.current(-5));
        assertEquals(3, PageParams.current(3));
    }

    @Test
    void size_clampedBetweenOneAndMax() {
        assertEquals(1, PageParams.size(0, 100));
        assertEquals(100, PageParams.size(1000, 100));
        assertEquals(20, PageParams.size(20, 100));
    }
}
