package com.github.wuzhudexiaopacai.ota.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
@TableName("ota_rollout")
public class OtaRollout {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    private Long firmwareId;

    private Long productId;

    private String name;

    /** DEVICE / SPACE / ALL */
    private String targetType;

    private Long targetSpaceId;

    /** PENDING / RUNNING / PAUSED / SUCCESS / PARTIAL_FAILED / FAILED / CANCELED */
    private String status;

    private Integer batchSize;

    private Integer failureThresholdPercent;

    private Integer maxAttempts;

    private Boolean autoAdvance;

    private String pauseReason;

    private Integer currentBatch;

    /** 已完成失败率评审的批次，避免恢复后重复熔断 */
    private Integer reviewedBatch;

    private Integer totalCount;

    private Integer pendingCount;

    private Integer runningCount;

    private Integer successCount;

    private Integer failedCount;

    private String createdBy;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    private OffsetDateTime startedAt;

    private OffsetDateTime finishedAt;
}
