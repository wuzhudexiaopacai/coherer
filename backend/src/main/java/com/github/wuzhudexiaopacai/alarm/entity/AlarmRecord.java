package com.github.wuzhudexiaopacai.alarm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
@TableName("alarm_record")
public class AlarmRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    private Long ruleId;

    private Long productId;

    private String deviceId;

    private String level;

    /** TRIGGERED / ACKNOWLEDGED / RECOVERED / CLOSED */
    private String status;

    private String message;

    private Double value;

    private OffsetDateTime triggeredAt;

    private OffsetDateTime ackedAt;

    private OffsetDateTime recoveredAt;

    private OffsetDateTime closedAt;
}
