package com.github.wuzhudexiaopacai.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    /** 租户代码；平台管理员使用其所属租户（默认 default） */
    @NotBlank(message = "租户代码不能为空")
    private String tenantCode;

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
