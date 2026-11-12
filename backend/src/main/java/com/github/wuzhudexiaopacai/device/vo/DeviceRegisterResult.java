package com.github.wuzhudexiaopacai.device.vo;

import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DeviceRegisterResult {

    private String deviceId;

    private String name;

    /** 设备密钥明文，仅注册时返回一次，请立即保存 */
    private String deviceSecret;
}
