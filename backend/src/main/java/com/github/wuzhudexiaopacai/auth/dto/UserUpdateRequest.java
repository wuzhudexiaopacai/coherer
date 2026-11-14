package com.github.wuzhudexiaopacai.auth.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserUpdateRequest {

    @Size(max = 64, message = "昵称最长 64 字符")
    private String nickname;

    @Pattern(regexp = "ADMIN|OPERATOR|VIEWER", message = "角色只能是 ADMIN/OPERATOR/VIEWER")
    private String role;

    /** 数据范围空间；传 0 表示改为全平台 */
    private Long spaceId;

    @Pattern(regexp = "ACTIVE|DISABLED", message = "状态只能是 ACTIVE/DISABLED")
    private String status;
}
