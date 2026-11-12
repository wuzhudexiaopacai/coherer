package com.github.wuzhudexiaopacai.internal.auth.dto;

import lombok.Data;

/** EMQX HTTP 认证回调请求体（字段名与 EMQX 模板一致）。 */
@Data
public class MqttAuthRequest {

    private String clientid;

    private String username;

    private String password;
}
