package com.github.wuzhudexiaopacai.alarm.vo;

import com.github.wuzhudexiaopacai.alarm.ConditionNode;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class AlarmRuleVO {

    private Long id;

    private Long productId;

    private String name;

    private String propertyIdentifier;

    private String operator;

    private Double threshold;

    private ConditionNode condition;

    /** 条件可读摘要，如 "temperature > 30 AND humidity < 60" */
    private String conditionText;

    private Integer durationSeconds;

    private String level;

    private Boolean enabled;

    private OffsetDateTime createdAt;
}
