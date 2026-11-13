package com.github.wuzhudexiaopacai.alarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AlarmConditionNodeTest {

    @Test
    void evaluate_andGroup_requiresAllConditions() {
        ConditionNode tree = group("AND",
                leaf("temperature", "GT", 30, null),
                leaf("humidity", "LT", 60, null));

        assertTrue(AlarmCondition.evaluate(tree, Map.of("temperature", 35, "humidity", 50), false));
        assertFalse(AlarmCondition.evaluate(tree, Map.of("temperature", 35, "humidity", 70), false));
        assertFalse(AlarmCondition.evaluate(tree, Map.of("temperature", 20, "humidity", 50), false));
    }

    @Test
    void evaluate_orGroup_requiresAnyCondition() {
        ConditionNode tree = group("OR",
                leaf("temperature", "GT", 30, null),
                leaf("humidity", "GT", 60, null));

        assertTrue(AlarmCondition.evaluate(tree, Map.of("temperature", 20, "humidity", 70), false));
        assertTrue(AlarmCondition.evaluate(tree, Map.of("temperature", 35, "humidity", 50), false));
        assertFalse(AlarmCondition.evaluate(tree, Map.of("temperature", 20, "humidity", 50), false));
    }

    @Test
    void evaluate_nestedGroups_supported() {
        ConditionNode tree = group("AND",
                leaf("temperature", "GT", 30, null),
                group("OR",
                        leaf("humidity", "LT", 40, null),
                        leaf("co2", "GT", 1000, null)));

        assertTrue(AlarmCondition.evaluate(tree,
                Map.of("temperature", 32, "humidity", 35, "co2", 500), false));
        assertTrue(AlarmCondition.evaluate(tree,
                Map.of("temperature", 32, "humidity", 55, "co2", 1200), false));
        assertFalse(AlarmCondition.evaluate(tree,
                Map.of("temperature", 32, "humidity", 55, "co2", 500), false));
    }

    @Test
    void evaluate_recoverThreshold_appliesHysteresis() {
        ConditionNode leaf = leaf("temperature", "GT", 30, 25.0);

        assertTrue(AlarmCondition.evaluate(leaf, Map.of("temperature", 31), false));
        assertFalse(AlarmCondition.evaluate(leaf, Map.of("temperature", 27), false));
        // 活动期：27 仍高于恢复阈值 25，保持告警
        assertTrue(AlarmCondition.evaluate(leaf, Map.of("temperature", 27), true));
        // 活动期：24 低于恢复阈值，恢复
        assertFalse(AlarmCondition.evaluate(leaf, Map.of("temperature", 24), true));
    }

    @Test
    void summary_rendersReadableCondition() {
        ConditionNode tree = group("AND",
                leaf("temperature", "GT", 30, null),
                leaf("humidity", "LT", 60, null));

        assertEquals("(temperature > 30.0 AND humidity < 60.0)", tree.summary());
    }

    @Test
    void evaluate_missingProperty_returnsFalse() {
        ConditionNode leaf = leaf("pressure", "GT", 100, null);
        assertFalse(AlarmCondition.evaluate(leaf, Map.of("temperature", 31), false));
    }

    private ConditionNode leaf(String property, String operator, double threshold, Double recover) {
        ConditionNode node = new ConditionNode();
        node.setProperty(property);
        node.setOperator(operator);
        node.setThreshold(threshold);
        node.setRecoverThreshold(recover);
        return node;
    }

    private ConditionNode group(String logic, ConditionNode... items) {
        ConditionNode node = new ConditionNode();
        node.setLogic(logic);
        node.setItems(List.of(items));
        return node;
    }
}
