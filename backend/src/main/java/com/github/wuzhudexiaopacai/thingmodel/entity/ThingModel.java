package com.github.wuzhudexiaopacai.thingmodel.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Data;

@Data
@TableName(value = "thing_model", autoResultMap = true)
public class ThingModel {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long productId;

    /** PROPERTY / EVENT / SERVICE */
    private String type;

    private String identifier;

    private String name;

    /** INT / FLOAT / DOUBLE / BOOL / STRING（属性） */
    private String dataType;

    /** R / RW */
    private String accessMode;

    private String unit;

    /** 扩展定义：min/max 或事件、服务参数 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> spec;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
