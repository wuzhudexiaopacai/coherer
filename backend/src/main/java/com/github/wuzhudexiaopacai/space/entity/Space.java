package com.github.wuzhudexiaopacai.space.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
@TableName("space")
public class Space {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    private Long parentId;

    private String name;

    private String code;

    /** PROJECT / AREA / BUILDING / FLOOR / ROOM / GENERIC */
    private String type;

    private Integer sort;

    private String description;

    /** ACTIVE / DISABLED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
