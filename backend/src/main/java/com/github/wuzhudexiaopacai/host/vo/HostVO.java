package com.github.wuzhudexiaopacai.host.vo;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class HostVO {

    private Long id;

    private String hostId;

    private String name;

    private Long projectId;

    private Long spaceId;

    private String model;

    private String firmwareVersion;

    private String status;

    private OffsetDateTime lastOnlineAt;

    private OffsetDateTime createdAt;

    /** 绑定的子设备数量 */
    private Long childCount;
}
