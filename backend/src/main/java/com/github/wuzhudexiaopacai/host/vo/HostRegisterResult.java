package com.github.wuzhudexiaopacai.host.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class HostRegisterResult {

    private String hostId;

    private String name;

    /** 主机密钥明文，仅注册/轮换时返回一次 */
    private String hostSecret;
}
