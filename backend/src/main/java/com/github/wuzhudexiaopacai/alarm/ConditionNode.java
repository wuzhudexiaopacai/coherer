package com.github.wuzhudexiaopacai.alarm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Data;

/**
 * 告警条件树：叶子节点为单属性比较，组节点用 logic(AND/OR) 组合子节点。
 *
 * <p>recoverThreshold 为恢复阈值（滞回）：告警处于活动状态时使用该阈值判断恢复，
 * 用于避免数值在阈值附近抖动导致反复触发/恢复。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConditionNode {

    private String logic;

    private List<ConditionNode> items;

    private String property;

    /** GT/GE/LT/LE/EQ/NE */
    private String operator;

    private Double threshold;

    private Double recoverThreshold;

    @JsonIgnore
    public boolean isGroup() {
        return items != null && !items.isEmpty();
    }

    public ConditionNode firstLeaf() {
        if (!isGroup()) {
            return this;
        }
        for (ConditionNode item : items) {
            ConditionNode leaf = item.firstLeaf();
            if (leaf != null) {
                return leaf;
            }
        }
        return null;
    }

    /** 生成展示文案，如 "temperature > 30 AND humidity < 60"。 */
    public String summary() {
        if (isGroup()) {
            String joiner = "OR".equalsIgnoreCase(logic) ? " OR " : " AND ";
            return "(" + items.stream()
                    .map(ConditionNode::summary)
                    .reduce((a, b) -> a + joiner + b)
                    .orElse("") + ")";
        }
        if (property == null) {
            return "";
        }
        return property + " " + AlarmCondition.symbol(operator) + " " + threshold;
    }
}
