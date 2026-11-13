package com.github.wuzhudexiaopacai.alarm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
@TableName("alarm_rule")
public class AlarmRule {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    private Long productId;

    private String name;

    private String propertyIdentifier;

    /** GT / GE / LT / LE / EQ */
    private String operator;

    private Double threshold;

    /** 条件持续秒数，0 表示立即触发 */
    private Integer durationSeconds;

    /** INFO / WARNING / CRITICAL */
    private String level;

    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
