package com.github.wuzhudexiaopacai.thingmodel.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class ThingModelSaveRequest {

    @NotEmpty(message = "物模型不能为空")
    @Valid
    private List<Item> items;

    @Data
    public static class Item {

        @NotBlank(message = "type 不能为空")
        @Pattern(regexp = "PROPERTY|EVENT|SERVICE",
                message = "type 只能是 PROPERTY / EVENT / SERVICE")
        private String type;

        @NotBlank(message = "identifier 不能为空")
        @Pattern(regexp = "^[a-zA-Z_][a-zA-Z0-9_]{0,63}$",
                message = "identifier 只能包含字母、数字、下划线，且以字母或下划线开头")
        private String identifier;

        @NotBlank(message = "名称不能为空")
        @Size(max = 64, message = "名称最长 64 字符")
        private String name;

        @Pattern(regexp = "INT|FLOAT|DOUBLE|BOOL|STRING",
                message = "dataType 只能是 INT / FLOAT / DOUBLE / BOOL / STRING")
        private String dataType;

        @Pattern(regexp = "R|RW", message = "accessMode 只能是 R 或 RW")
        private String accessMode;

        @Size(max = 16, message = "单位最长 16 字符")
        private String unit;

        /** 扩展定义：{"min":0,"max":100} 或事件/服务参数 */
        private Map<String, Object> spec;
    }
}
