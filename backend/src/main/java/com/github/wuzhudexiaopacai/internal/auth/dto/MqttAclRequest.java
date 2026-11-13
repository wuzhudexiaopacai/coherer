package com.github.wuzhudexiaopacai.internal.auth.dto;

import lombok.Data;

/** EMQX HTTP ACL 回调请求体。 */
@Data
public class MqttAclRequest {

    private String clientid;

    private String username;

    /** 发布/订阅的 topic 或 topic filter */
    private String topic;

    /** publish / subscribe */
    private String action;
}
