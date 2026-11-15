package com.github.wuzhudexiaopacai.ota.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
@TableName("ota_rollout_device")
public class OtaRolloutDevice {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long rolloutId;

    private String deviceId;

    private Integer batchNo;

    /** PENDING / RUNNING / SUCCESS / FAILED / SKIPPED */
    private String status;

    private Long taskId;

    private String error;

    private OffsetDateTime updatedAt;
}
