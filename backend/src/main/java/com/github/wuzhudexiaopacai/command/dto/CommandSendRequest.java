package com.github.wuzhudexiaopacai.command.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

    /** 可选幂等键：同一设备重复提交相同键返回首次结果 */
    @Size(max = 64, message = "幂等键最长 64 字符")
    @Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "幂等键只能包含字母/数字/_/-")
    private String clientRequestId;
}
