package com.github.wuzhudexiaopacai.telemetry.vo;

import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Data;

@Data
public class TelemetryVO {

    private String deviceId;

    private OffsetDateTime ts;

    private Map<String, Object> params;

    private OffsetDateTime receivedAt;
}
