package com.github.wuzhudexiaopacai.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserCreateRequest {

    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]{2,31}$",
            message = "用户名需以字母开头，3-32 位字母/数字/下划线")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
            message = "密码需 8-64 位且同时包含字母和数字")
    private String password;

    @NotBlank(message = "昵称不能为空")
    @Size(max = 64, message = "昵称最长 64 字符")
    private String nickname;

    @Pattern(regexp = "ADMIN|OPERATOR|VIEWER", message = "角色只能是 ADMIN/OPERATOR/VIEWER")
    private String role = "VIEWER";

    /** 数据范围空间；为空表示全平台 */
    private Long spaceId;
}
