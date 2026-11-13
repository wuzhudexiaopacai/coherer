package com.github.wuzhudexiaopacai.auth.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AuthResponse {

    private String accessToken;

    private String refreshToken;

    private long expiresInSeconds;

    private UserInfoVO user;
}
