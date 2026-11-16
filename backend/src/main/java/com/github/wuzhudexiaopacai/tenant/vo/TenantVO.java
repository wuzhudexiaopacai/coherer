package com.github.wuzhudexiaopacai.tenant.vo;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class TenantVO {

    private String id;

    private String name;

    private String status;

    private String plan;

    private String description;

    private Long userCount;

    private Long projectCount;

    private Long deviceCount;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
