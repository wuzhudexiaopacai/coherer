package com.github.wuzhudexiaopacai.shadow.vo;

import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Data;

@Data
public class ShadowVO {

    private String deviceId;

    private Map<String, Object> desired;

    private Map<String, Object> reported;

    private Long version;

    private OffsetDateTime updatedAt;
}
