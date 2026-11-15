package com.github.wuzhudexiaopacai.ota.vo;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class OtaTaskVO {

    private Long id;

    private Long firmwareId;

    private String firmwareVersion;

    private String deviceId;

    private String status;

    private Integer progress;

    private String error;

    private Long rolloutId;

    private Integer batchNo;

    private String createdBy;

    private OffsetDateTime createdAt;

    private OffsetDateTime sentAt;

    private OffsetDateTime finishedAt;
}
