package com.github.wuzhudexiaopacai.alarm.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AlarmRuleRequest {

    @NotNull(message = "productId 不能为空")
    private Long productId;

    @NotBlank(message = "规则名称不能为空")
    @Size(max = 64, message = "规则名称最长 64 字符")
    private String name;

    @NotBlank(message = "属性标识不能为空")
    @Pattern(regexp = "^[a-zA-Z_][a-zA-Z0-9_]{0,63}$",
            message = "属性标识只能包含字母、数字、下划线")
    private String propertyIdentifier;

    @NotBlank(message = "比较符不能为空")
    @Pattern(regexp = "GT|GE|LT|LE|EQ", message = "operator 只能是 GT/GE/LT/LE/EQ")
    private String operator;

    @NotNull(message = "阈值不能为空")
    private Double threshold;

    @Min(value = 0, message = "持续时长不能为负")
    @Max(value = 86400, message = "持续时长最大 86400 秒")
    private Integer durationSeconds = 0;

    @Pattern(regexp = "INFO|WARNING|CRITICAL", message = "level 只能是 INFO/WARNING/CRITICAL")
    private String level = "WARNING";

    private Boolean enabled = true;
}
