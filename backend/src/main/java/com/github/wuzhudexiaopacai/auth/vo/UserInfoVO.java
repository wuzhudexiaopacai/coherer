package com.github.wuzhudexiaopacai.auth.vo;

import lombok.Data;

@Data
public class UserInfoVO {

    private Long id;

    private String username;

    private String nickname;

    /** ADMIN / OPERATOR / VIEWER */
    private String role;

    /** 数据范围：空间 ID；NULL 表示全部空间 */
    private Long spaceId;
}
