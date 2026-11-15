package com.github.wuzhudexiaopacai.ota.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OtaStartRequest {

    @NotNull(message = "固件不能为空")
    private Long firmwareId;

    @NotBlank(message = "设备不能为空")
    private String deviceId;

    /** 失败/超时后的最大尝试次数，默认 1（不重试） */
    @Min(value = 1, message = "最大尝试次数至少 1")
    @Max(value = 5, message = "最大尝试次数最多 5")
    private Integer maxAttempts;
}
