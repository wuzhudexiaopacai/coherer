package com.github.wuzhudexiaopacai.project.vo;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class ProjectVO {

    private Long id;

    private String code;

    private String name;

    private String status;

    private String description;

    private Long spaceCount;

    private Long hostCount;

    private Long deviceCount;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
