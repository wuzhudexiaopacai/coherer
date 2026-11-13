package com.github.wuzhudexiaopacai.alarm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.github.wuzhudexiaopacai.alarm.ConditionNode;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
@TableName(value = "alarm_rule", autoResultMap = true)
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

    /** 条件树（多条件 AND/OR + 恢复阈值），求值真相源 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private ConditionNode conditionTree;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
