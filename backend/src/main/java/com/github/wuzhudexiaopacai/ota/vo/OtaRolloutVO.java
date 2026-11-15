package com.github.wuzhudexiaopacai.ota.vo;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class OtaRolloutVO {

    private Long id;

    private Long firmwareId;

    private String firmwareVersion;

    private Long productId;

    private String name;

    private String targetType;

    private Long targetSpaceId;

    private String status;

    private Integer batchSize;

    private Integer failureThresholdPercent;

    private Boolean autoAdvance;

    private String pauseReason;

    private Integer currentBatch;

    private Integer totalBatches;

    private Integer totalCount;

    private Integer pendingCount;

    private Integer runningCount;

    private Integer successCount;

    private Integer failedCount;

    private Integer skippedCount;

    private String createdBy;

    private OffsetDateTime createdAt;

    private OffsetDateTime startedAt;

    private OffsetDateTime finishedAt;
}
