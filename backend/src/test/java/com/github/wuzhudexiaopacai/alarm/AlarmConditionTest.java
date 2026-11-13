package com.github.wuzhudexiaopacai.alarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AlarmConditionTest {

    @Test
    void matches_gt_returnsTrueOnlyAboveThreshold() {
        assertTrue(AlarmCondition.matches(30.1, "GT", 30));
        assertFalse(AlarmCondition.matches(30.0, "GT", 30));
    }

    @Test
    void matches_ge_returnsTrueAtThreshold() {
        assertTrue(AlarmCondition.matches(30.0, "GE", 30));
        assertFalse(AlarmCondition.matches(29.99, "GE", 30));
    }

    @Test
    void matches_lt_and_le() {
        assertTrue(AlarmCondition.matches(19.9, "LT", 20));
        assertTrue(AlarmCondition.matches(20.0, "LE", 20));
        assertFalse(AlarmCondition.matches(20.1, "LE", 20));
    }

    @Test
    void matches_eq_usesTolerance() {
        assertTrue(AlarmCondition.matches(42.0000000001, "EQ", 42));
        assertFalse(AlarmCondition.matches(42.2, "EQ", 42));
    }

    @Test
    void matches_ne_returnsTrueWhenDifferent() {
        assertTrue(AlarmCondition.matches(1, "NE", 2));
        assertFalse(AlarmCondition.matches(2, "NE", 2));
    }

    @Test
    void matches_unknownOperator_returnsFalse() {
        assertFalse(AlarmCondition.matches(1, "XX", 2));
        assertFalse(AlarmCondition.matches(1, null, 2));
    }

    @Test
    void symbol_mapsOperators() {
        assertEquals(">", AlarmCondition.symbol("GT"));
        assertEquals("≥", AlarmCondition.symbol("GE"));
        assertEquals("<", AlarmCondition.symbol("LT"));
        assertEquals("≤", AlarmCondition.symbol("LE"));
        assertEquals("=", AlarmCondition.symbol("EQ"));
        assertEquals("≠", AlarmCondition.symbol("NE"));
    }
}
