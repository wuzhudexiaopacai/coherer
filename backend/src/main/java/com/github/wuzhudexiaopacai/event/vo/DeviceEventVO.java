package com.github.wuzhudexiaopacai.event.vo;

import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Data;

@Data
public class DeviceEventVO {

    private Long id;

    private String deviceId;

    private String identifier;

    private String eventId;

    private Long ts;

    private Map<String, Object> params;

    private OffsetDateTime createdAt;
}
