package com.github.wuzhudexiaopacai.alarm;

import java.util.Map;
import java.util.Objects;

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
            case "NE" -> Math.abs(value - threshold) >= 1e-9;
            default -> false;
        };
    }

    /**
     * 递归评估条件树。
     *
     * @param recovery true 表示处于告警活动期，使用 recoverThreshold（若配置）判断是否恢复
     */
    public static boolean evaluate(ConditionNode node, Map<String, Object> params, boolean recovery) {
        if (node == null) {
            return false;
        }
        if (node.isGroup()) {
            boolean and = !"OR".equalsIgnoreCase(node.getLogic());
            return and
                    ? node.getItems().stream().allMatch(item -> evaluate(item, params, recovery))
                    : node.getItems().stream().anyMatch(item -> evaluate(item, params, recovery));
        }
        if (node.getProperty() == null || node.getThreshold() == null) {
            return false;
        }
        Object raw = params.get(node.getProperty());
        if (!(raw instanceof Number number)) {
            return false;
        }
        double threshold = recovery && node.getRecoverThreshold() != null
                ? node.getRecoverThreshold()
                : node.getThreshold();
        return matches(number.doubleValue(), node.getOperator(), threshold);
    }

    /** 条件树中第一个数值属性的当前值（用于告警记录快照）。 */
    public static Double firstNumericValue(ConditionNode node, Map<String, Object> params) {
        if (node == null) {
            return null;
        }
        if (node.isGroup()) {
            for (ConditionNode item : node.getItems()) {
                Double value = firstNumericValue(item, params);
                if (value != null) {
                    return value;
                }
            }
            return null;
        }
        Object raw = params.get(node.getProperty());
        return raw instanceof Number number ? number.doubleValue() : null;
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
            case "NE" -> "≠";
            default -> operator;
        };
    }
}

