package com.github.wuzhudexiaopacai.alarm.vo;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class AlarmRecordVO {

    private Long id;

    private Long ruleId;

    private String ruleName;

    private Long productId;

    private String deviceId;

    private String level;

    private String status;

    private String message;

    private Double value;

    private OffsetDateTime triggeredAt;

    private OffsetDateTime ackedAt;

    private OffsetDateTime recoveredAt;

    private OffsetDateTime closedAt;
}
