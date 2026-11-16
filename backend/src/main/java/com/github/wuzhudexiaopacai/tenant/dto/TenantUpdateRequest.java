package com.github.wuzhudexiaopacai.tenant.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TenantUpdateRequest {

    @Size(max = 64, message = "租户名称最长 64 字符")
    private String name;

    @Pattern(regexp = "ACTIVE|DISABLED", message = "状态只能是 ACTIVE 或 DISABLED")
    private String status;

    @Size(max = 32, message = "套餐最长 32 字符")
    private String plan;

    @Size(max = 255, message = "描述最长 255 字符")
    private String description;
}
