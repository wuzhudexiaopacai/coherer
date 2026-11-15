package com.github.wuzhudexiaopacai.ota.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OtaStartRequest {

    @NotNull(message = "固件不能为空")
    private Long firmwareId;

    @NotBlank(message = "设备不能为空")
    private String deviceId;
}
