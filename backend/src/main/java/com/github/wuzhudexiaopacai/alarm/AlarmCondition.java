package com.github.wuzhudexiaopacai.alarm;

/** 告警条件判断（纯函数，便于单元测试）。 */
public final class AlarmCondition {

    private AlarmCondition() {
    }

    public static boolean matches(double value, String operator, double threshold) {
        if (operator == null) {
            return false;
        }
        return switch (operator) {
            case "GT" -> value > threshold;
            case "GE" -> value >= threshold;
            case "LT" -> value < threshold;
            case "LE" -> value <= threshold;
            case "EQ" -> Math.abs(value - threshold) < 1e-9;
            default -> false;
        };
    }

    public static String symbol(String operator) {
        if (operator == null) {
            return "?";
        }
        return switch (operator) {
            case "GT" -> ">";
            case "GE" -> "≥";
            case "LT" -> "<";
            case "LE" -> "≤";
            case "EQ" -> "=";
            default -> operator;
        };
    }
}
