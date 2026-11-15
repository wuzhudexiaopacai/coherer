package com.github.wuzhudexiaopacai.ota.vo;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class FirmwareVO {

    private Long id;

    private Long productId;

    private String version;

    private String fileName;

    private Long sizeBytes;

    private String sha256;

    private String storageType;

    private String createdBy;

    private OffsetDateTime createdAt;
}
