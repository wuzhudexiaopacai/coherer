package com.github.wuzhudexiaopacai.shadow.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.Map;
import lombok.Data;

@Data
public class ShadowDesiredRequest {

    @NotEmpty(message = "params 不能为空")
    private Map<String, Object> params;

    /** 可选：期望的当前影子版本号，用于乐观锁防覆盖 */
    private Long version;
}
