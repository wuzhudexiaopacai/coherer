package com.github.wuzhudexiaopacai.ota.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class OtaRolloutPauseRequest {

    @Size(max = 200, message = "暂停原因最长 200")
    private String reason;
}
