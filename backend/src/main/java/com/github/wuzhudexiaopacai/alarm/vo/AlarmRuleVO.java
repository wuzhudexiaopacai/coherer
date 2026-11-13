package com.github.wuzhudexiaopacai.alarm.vo;

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

    private Integer durationSeconds;

    private String level;

    private Boolean enabled;

    private OffsetDateTime createdAt;
}
