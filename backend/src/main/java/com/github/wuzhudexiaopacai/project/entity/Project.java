package com.github.wuzhudexiaopacai.project.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
@TableName("project")
public class Project {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    private String code;

    private String name;

    /** ACTIVE / DISABLED */
    private String status;

    private String description;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
