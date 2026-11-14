package com.github.wuzhudexiaopacai.command.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import lombok.Data;

@Data
public class CommandSendRequest {

    @NotBlank(message = "服务标识不能为空")
    @Pattern(regexp = "^[a-zA-Z_][a-zA-Z0-9_]{0,63}$",
            message = "服务标识只能包含字母、数字、下划线")
    private String identifier;

    /** 服务入参，按物模型 SERVICE 定义传入 */
    private Map<String, Object> params;
}
