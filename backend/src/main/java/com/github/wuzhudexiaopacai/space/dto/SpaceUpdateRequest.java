package com.github.wuzhudexiaopacai.space.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SpaceUpdateRequest {

    private Long parentId;

    @Size(max = 64, message = "空间名称最长 64 字符")
    private String name;

    @Size(max = 64, message = "空间编码最长 64 字符")
    private String code;

    @Pattern(regexp = "PROJECT|AREA|BUILDING|FLOOR|ROOM|GENERIC",
            message = "type 只能是 PROJECT/AREA/BUILDING/FLOOR/ROOM/GENERIC")
    private String type;

    private Integer sort;

    @Size(max = 255, message = "描述最长 255 字符")
    private String description;

    @Pattern(regexp = "ACTIVE|DISABLED", message = "status 只能是 ACTIVE 或 DISABLED")
    private String status;
}
