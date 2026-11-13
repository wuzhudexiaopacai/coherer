package com.github.wuzhudexiaopacai.auth.vo;

import lombok.Data;

@Data
public class UserInfoVO {

    private Long id;

    private String username;

    private String nickname;

    /** ADMIN / OPERATOR / VIEWER */
    private String role;
}
