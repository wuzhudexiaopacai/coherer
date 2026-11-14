package com.github.wuzhudexiaopacai.host.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class HostUpdateRequest {

    @Size(max = 64, message = "主机名称最长 64 字符")
    private String name;

    private Long spaceId;

    @Size(max = 64, message = "型号最长 64 字符")
    private String model;

    @Size(max = 64, message = "固件版本最长 64 字符")
    private String firmwareVersion;
}
