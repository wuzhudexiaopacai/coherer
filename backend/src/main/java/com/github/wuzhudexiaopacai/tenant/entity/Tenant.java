package com.github.wuzhudexiaopacai.tenant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
@TableName("tenant")
public class Tenant {

    /** 租户代码，同时作为 tenant_id */
    @TableId(type = IdType.INPUT)
    private String id;

    private String name;

    /** ACTIVE / DISABLED */
    private String status;

    private String plan;

    private String description;

    private Long defaultProjectId;

    private Long defaultSpaceId;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
