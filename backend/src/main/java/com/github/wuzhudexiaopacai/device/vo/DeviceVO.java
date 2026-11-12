package com.github.wuzhudexiaopacai.device.vo;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class DeviceVO {

    private Long id;

    private Long productId;

    private String deviceId;

    private String name;

    private String status;

    private OffsetDateTime lastOnlineAt;

    private OffsetDateTime createdAt;
}
