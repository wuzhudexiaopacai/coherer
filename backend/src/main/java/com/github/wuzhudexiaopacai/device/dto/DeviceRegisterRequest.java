package com.github.wuzhudexiaopacai.device.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DeviceRegisterRequest {

    @NotNull(message = "productId 不能为空")
    private Long productId;

    /** 可选：归属空间，不传则落到默认空间 */
    private Long spaceId;

    @Min(value = 1, message = "单次注册至少 1 台")
    @Max(value = 1000, message = "单次注册最多 1000 台")
    private Integer count = 1;

    @Size(max = 32, message = "设备名前缀最长 32 字符")
    private String namePrefix;
}
