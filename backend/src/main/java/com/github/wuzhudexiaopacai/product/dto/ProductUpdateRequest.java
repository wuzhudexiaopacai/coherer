package com.github.wuzhudexiaopacai.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProductUpdateRequest {

    @NotBlank(message = "产品名称不能为空")
    @Size(max = 64, message = "产品名称最长 64 字符")
    private String name;

    @Size(max = 32, message = "品类最长 32 字符")
    private String category;

    @Pattern(regexp = "DIRECT|GATEWAY", message = "nodeType 只能是 DIRECT 或 GATEWAY")
    private String nodeType;

    @Pattern(regexp = "ACTIVE|DISABLED", message = "status 只能是 ACTIVE 或 DISABLED")
    private String status;

    @Size(max = 255, message = "描述最长 255 字符")
    private String description;
}
