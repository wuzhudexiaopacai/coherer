package com.github.wuzhudexiaopacai.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TenantCreateRequest {

    /** 租户代码，同时作为 tenant_id，不可修改 */
    @NotBlank(message = "租户代码不能为空")
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_-]{2,31}$",
            message = "租户代码需以字母开头，3-32 位字母/数字/下划线/短横线")
    private String code;

    @NotBlank(message = "租户名称不能为空")
    @Size(max = 64, message = "租户名称最长 64 字符")
    private String name;

    @Size(max = 32, message = "套餐最长 32 字符")
    private String plan;

    @Size(max = 255, message = "描述最长 255 字符")
    private String description;

    @NotBlank(message = "初始管理员用户名不能为空")
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{2,31}$",
            message = "用户名需以字母开头，3-32 位字母/数字/下划线")
    private String adminUsername;

    @NotBlank(message = "初始管理员密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度 8-64")
    private String adminPassword;

    @Size(max = 64, message = "管理员昵称最长 64 字符")
    private String adminNickname;
}
