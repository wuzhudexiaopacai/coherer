package com.github.wuzhudexiaopacai.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProjectCreateRequest {

    @NotBlank(message = "项目名称不能为空")
    @Size(max = 64, message = "项目名称最长 64 字符")
    private String name;

    @Size(max = 64, message = "项目编码最长 64 字符")
    @Pattern(regexp = "^[A-Za-z0-9._-]*$", message = "项目编码只能包含字母、数字、点、下划线、短横线")
    private String code;

    @Size(max = 255, message = "描述最长 255 字符")
    private String description;

    @Pattern(regexp = "ACTIVE|DISABLED", message = "状态只能是 ACTIVE 或 DISABLED")
    private String status = "ACTIVE";
}
