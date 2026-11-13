package com.github.wuzhudexiaopacai.alarm.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 规则更新：仅更新非空字段（前端主要使用 enabled 开关与阈值调整）。 */
@Data
public class AlarmRuleUpdateRequest {

    @Size(max = 64, message = "规则名称最长 64 字符")
    private String name;

    private Double threshold;

    @Min(value = 0, message = "持续时长不能为负")
    @Max(value = 86400, message = "持续时长最大 86400 秒")
    private Integer durationSeconds;

    @Pattern(regexp = "INFO|WARNING|CRITICAL", message = "level 只能是 INFO/WARNING/CRITICAL")
    private String level;

    private Boolean enabled;
}
